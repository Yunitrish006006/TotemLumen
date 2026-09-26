package dev.totem.lumen.gui;

import com.mojang.blaze3d.Blaze3D;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.totem.lumen.world.EffectiveLightingRules;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Local integrated-server data-pack operations; multiplayer remains read-only. */
public final class ClientLightingPackManager {
    public static final String DEFAULT_PACK_ID = "totem-lumen:default_lighting";
    public static final String TEST_PACK_ID = "totem-lumen:double_light_test";
    private static final String CUSTOM_PREFIX = "totem-lumen-lighting-";
    private static final String FILE_SLASH_PREFIX = "file/" + CUSTOM_PREFIX;
    private static final String FILE_COLON_PREFIX = "file:" + CUSTOM_PREFIX;

    /** Larger order means higher pack priority; disabled packs use -1. */
    public record PackInfo(String id, String title, boolean enabled, boolean required, int order) {}

    private ClientLightingPackManager() {}

    public static boolean canManage(Minecraft client) {
        return client.hasSingleplayerServer() && client.getSingleplayerServer() != null;
    }

    /** Only folders created by this editor may be updated; built-in and third-party packs stay read-only. */
    public static boolean canEditExisting(String packId) {
        return editableName(packId) != null;
    }

    private static String editableName(String packId) {
        if (packId == null) return null;
        String name = packId.startsWith(FILE_SLASH_PREFIX)
                ? packId.substring(FILE_SLASH_PREFIX.length())
                : packId.startsWith(FILE_COLON_PREFIX)
                        ? packId.substring(FILE_COLON_PREFIX.length()) : null;
        return name != null && name.matches("[a-z0-9_-]{1,32}") ? name : null;
    }

    public static void list(Minecraft client, Consumer<List<PackInfo>> success,
                            Consumer<Throwable> failure) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            failure.accept(new IllegalStateException("No integrated server"));
            return;
        }
        server.execute(() -> {
            try {
                PackRepository repository = server.getPackRepository();
                repository.reload();
                List<String> selected = selectedIds(repository);
                List<PackInfo> result = repository.getAvailablePacks().stream()
                        .filter(pack -> managed(pack.getId()))
                        .map(pack -> new PackInfo(pack.getId(), pack.getTitle().getString(),
                                selected.contains(pack.getId()), pack.isRequired(),
                                selected.indexOf(pack.getId())))
                        .sorted(Comparator.comparing(PackInfo::id))
                        .toList();
                client.execute(() -> success.accept(result));
            } catch (Throwable problem) {
                client.execute(() -> failure.accept(problem));
            }
        });
    }

    public static void setEnabled(Minecraft client, String packId, boolean enabled,
                              Consumer<Throwable> completed) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || !managed(packId)) {
            completed.accept(new IllegalArgumentException("Not a local lighting data pack"));
            return;
        }
        server.execute(() -> {
            try {
                PackRepository repository = server.getPackRepository();
                repository.reload();
                Pack pack = repository.getPack(packId);
                if (pack == null || (!enabled && pack.isRequired())) {
                    throw new IllegalArgumentException("Lighting data pack cannot be changed");
                }
                List<String> selected = selectedIds(repository);
                if (selected.contains(packId) == enabled) {
                    client.execute(() -> completed.accept(null));
                    return;
                }
                selected.remove(packId);
                if (enabled) {
                    int lastLighting = -1;
                    for (int index = 0; index < selected.size(); index++) {
                        if (managed(selected.get(index))) lastLighting = index;
                    }
                    selected.add(lastLighting < 0 ? selected.size() : lastLighting + 1, packId);
                }
                reload(server, selected, client, completed);
            } catch (Throwable problem) {
                client.execute(() -> completed.accept(problem));
            }
        });
    }

    /** Up means higher priority in the selected column (later in Minecraft's pack stack). */
    public static void move(Minecraft client, String packId, boolean up,
                            Consumer<Throwable> completed) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || !managed(packId)) {
            completed.accept(new IllegalArgumentException("Not a local lighting data pack"));
            return;
        }
        server.execute(() -> {
            try {
                PackRepository repository = server.getPackRepository();
                repository.reload();
                List<String> selected = selectedIds(repository);
                int current = selected.indexOf(packId);
                int target = -1;
                if (current >= 0) {
                    if (up) {
                        for (int index = current + 1; index < selected.size(); index++) {
                            if (managed(selected.get(index))) { target = index; break; }
                        }
                    } else {
                        for (int index = current - 1; index >= 0; index--) {
                            if (managed(selected.get(index))) { target = index; break; }
                        }
                    }
                }
                if (target < 0) throw new IllegalArgumentException("Lighting pack cannot move further");
                java.util.Collections.swap(selected, current, target);
                reload(server, selected, client, completed);
            } catch (Throwable problem) {
                client.execute(() -> completed.accept(problem));
            }
        });
    }

    public static void openFolder(Minecraft client) throws IOException {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) throw new IOException("No integrated server");
        Path folder = server.getWorldPath(LevelResource.DATAPACK_DIR).toAbsolutePath().normalize();
        Files.createDirectories(folder);
        dev.totem.lumen.TotemLumenClient.LOGGER.info("Opening lighting data-pack folder: {}", folder);
        Blaze3D.openPath(folder);
    }

    /** Saves every staged block rule into one fresh world data pack, then enables it. */
    public static void saveAs(Minecraft client, String name, List<LightingRuleDraft> drafts,
                              long expectedRevision,
                              Consumer<Throwable> completed) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            completed.accept(new IllegalArgumentException("No integrated server"));
            return;
        }
        if (!name.matches("[a-z0-9_-]{1,32}")) {
            completed.accept(new IllegalArgumentException("Use 1-32 lowercase letters, digits, _ or -"));
            return;
        }
        List<LightingRuleDraft> batch;
        try {
            batch = validatedBatch(drafts);
        } catch (IllegalArgumentException problem) {
            completed.accept(problem);
            return;
        }
        server.execute(() -> {
            try {
                validateCurrentRules(batch, expectedRevision);
                Path datapacks = server.getWorldPath(LevelResource.DATAPACK_DIR)
                        .toAbsolutePath().normalize();
                Files.createDirectories(datapacks);
                Path packFolder = datapacks.resolve(CUSTOM_PREFIX + name);
                if (Files.exists(packFolder)) throw new java.nio.file.FileAlreadyExistsException(packFolder.toString());
                Path staging = Files.createTempDirectory(datapacks.getParent(), ".totem-lumen-draft-");
                try {
                    writePack(staging, name, batch);
                    Files.move(staging, packFolder); // Never replace an existing world data pack.
                } finally {
                    if (Files.exists(staging)) cleanupStaging(staging);
                }

                PackRepository repository = server.getPackRepository();
                Set<String> before = Set.copyOf(repository.getAvailableIds());
                repository.reload();
                List<String> additions = repository.getAvailableIds().stream()
                        .filter(id -> !before.contains(id) && managed(id)).toList();
                if (additions.size() != 1) throw new IOException("New lighting pack was not recognized");
                List<String> selected = selectedIds(repository);
                selected.add(additions.getFirst());
                reload(server, selected, client, completed);
            } catch (Throwable problem) {
                client.execute(() -> completed.accept(problem));
            }
        });
    }

    /** Updates only an enabled, local folder that this editor previously created. */
    public static void saveToExisting(Minecraft client, String packId, List<LightingRuleDraft> drafts,
                                      long expectedRevision, Consumer<Throwable> completed) {
        MinecraftServer server = client.getSingleplayerServer();
        String name = editableName(packId);
        if (server == null || name == null) {
            completed.accept(new IllegalArgumentException("Select an editable local lighting pack"));
            return;
        }
        List<LightingRuleDraft> batch;
        try {
            batch = validatedBatch(drafts);
        } catch (IllegalArgumentException problem) {
            completed.accept(problem);
            return;
        }
        server.execute(() -> {
            Path transaction = null;
            Path original = null;
            boolean installed = false;
            try {
                validateCurrentRules(batch, expectedRevision);
                PackRepository repository = server.getPackRepository();
                repository.reload();
                List<String> selected = selectedIds(repository);
                if (repository.getPack(packId) == null || !selected.contains(packId)) {
                    throw new IOException("The selected lighting pack is no longer enabled");
                }
                Path datapacks = server.getWorldPath(LevelResource.DATAPACK_DIR)
                        .toAbsolutePath().normalize();
                original = datapacks.resolve(CUSTOM_PREFIX + name);
                if (!Files.isDirectory(original, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("The selected lighting pack is not a local folder");
                }
                transaction = Files.createTempDirectory(datapacks.getParent(), ".totem-lumen-edit-");
                Path staging = transaction.resolve("staging");
                Path backup = transaction.resolve("backup");
                copyPackForEdit(original, staging, name, batch);
                installEditedPack(original, staging, backup);
                installed = true;
                Path installedPack = original;
                Path completedTransaction = transaction;
                server.reloadResources(selected).whenComplete((ignored, reloadFailure) -> server.execute(() -> {
                    Throwable result = reloadFailure;
                    if (reloadFailure != null) {
                        try {
                            restoreEditedPack(installedPack, backup,
                                    completedTransaction.resolve("failed"));
                            repository.reload();
                        } catch (Throwable restoreFailure) {
                            result.addSuppressed(restoreFailure);
                            Throwable outcome = result;
                            client.execute(() -> completed.accept(outcome));
                            return;
                        }
                        Throwable failure = result;
                        server.reloadResources(selected).whenComplete((unused, restoreFailure) -> {
                            if (restoreFailure != null) failure.addSuppressed(restoreFailure);
                            try {
                                cleanupStaging(completedTransaction);
                            } catch (IOException cleanupFailure) {
                                failure.addSuppressed(cleanupFailure);
                            }
                            client.execute(() -> completed.accept(failure));
                        });
                        return;
                    }
                    try {
                        cleanupStaging(completedTransaction);
                    } catch (IOException cleanupFailure) {
                        dev.totem.lumen.TotemLumenClient.LOGGER.warn(
                                "Could not remove a completed lighting-pack backup", cleanupFailure);
                    }
                    Throwable outcome = result;
                    client.execute(() -> completed.accept(outcome));
                }));
            } catch (Throwable problem) {
                if (installed) {
                    try {
                        restoreEditedPack(original, transaction.resolve("backup"),
                                transaction.resolve("failed"));
                    } catch (IOException restoreFailure) {
                        problem.addSuppressed(restoreFailure);
                    }
                }
                if (transaction != null) {
                    // Preserve the backup for manual recovery if restoring the original failed.
                    if (!Files.exists(transaction.resolve("backup"))) {
                        try { cleanupStaging(transaction); }
                        catch (IOException cleanupFailure) { problem.addSuppressed(cleanupFailure); }
                    }
                }
                client.execute(() -> completed.accept(problem));
            }
        });
    }

    private static List<LightingRuleDraft> validatedBatch(List<LightingRuleDraft> drafts) {
        if (drafts == null || drafts.isEmpty() || drafts.size() > 8_192) {
            throw new IllegalArgumentException("Expected 1-8192 pending block rules");
        }
        List<LightingRuleDraft> batch = List.copyOf(drafts);
        Set<net.minecraft.resources.Identifier> ids = new HashSet<>();
        for (LightingRuleDraft draft : batch) {
            if (!draft.changed() || !ids.add(draft.id())) {
                throw new IllegalArgumentException("Duplicate or unchanged pending block rule");
            }
        }
        return batch;
    }

    private static void validateCurrentRules(List<LightingRuleDraft> batch, long expectedRevision)
            throws IOException {
        if (EffectiveLightingRules.current().revision() != expectedRevision) {
            throw new IOException("Server lighting rules changed; reopen preview before saving");
        }
        for (LightingRuleDraft draft : batch) {
            if (EffectiveLightingRules.current().origins().get(draft.id())
                    == EffectiveLightingRules.Origin.SERVER_CONFIG) {
                throw new IOException("Server config overrides " + draft.id());
            }
        }
    }

    /** Copies without following links, preserving unrelated rules and rejecting unsafe pack trees. */
    static void copyPackForEdit(Path original, Path staging, String name,
                                List<LightingRuleDraft> batch) throws IOException {
        Path metadata = original.resolve("pack.mcmeta");
        if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Lighting pack metadata is missing");
        }
        try {
            String description = JsonParser.parseString(Files.readString(metadata, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("pack").get("description").getAsString();
            if (!description.equals("Totem Lumen lighting: " + name)) {
                throw new IOException("Only packs created by the lighting editor can be updated");
            }
        } catch (RuntimeException invalidMetadata) {
            throw new IOException("Invalid lighting pack metadata", invalidMetadata);
        }
        try (Stream<Path> contents = Files.walk(original)) {
            for (Path source : contents.toList()) {
                if (Files.isSymbolicLink(source)) throw new IOException("Lighting pack contains a symbolic link");
                Path destination = staging.resolve(original.relativize(source));
                if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    Files.copy(source, destination);
                } else {
                    throw new IOException("Lighting pack contains an unsupported file");
                }
            }
        }
        for (LightingRuleDraft draft : batch) {
            Path rulePath = rulePath(staging.resolve("data"), draft);
            Files.createDirectories(rulePath.getParent());
            Files.writeString(rulePath, ruleContents(draft), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    static void installEditedPack(Path original, Path staging, Path backup) throws IOException {
        Files.move(original, backup);
        try {
            Files.move(staging, original);
        } catch (IOException installFailure) {
            try { Files.move(backup, original); }
            catch (IOException restoreFailure) { installFailure.addSuppressed(restoreFailure); }
            throw installFailure;
        }
    }

    static void restoreEditedPack(Path original, Path backup, Path failed) throws IOException {
        Files.move(original, failed);
        try {
            Files.move(backup, original);
        } catch (IOException restoreFailure) {
            // Keep a usable pack at the original path and preserve the old copy for recovery.
            try { Files.move(failed, original); }
            catch (IOException fallbackFailure) { restoreFailure.addSuppressed(fallbackFailure); }
            throw restoreFailure;
        }
    }

    static void writePack(Path staging, String name, List<LightingRuleDraft> batch) throws IOException {
        Path dataRoot = staging.resolve("data");
        for (LightingRuleDraft draft : batch) {
            Path rulePath = rulePath(dataRoot, draft);
            Files.createDirectories(rulePath.getParent());
            Files.writeString(rulePath, ruleContents(draft), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        }
        String metadata = "{\"pack\":{\"min_format\":121,\"max_format\":121,\"description\":\"Totem Lumen lighting: "
                + name + "\"}}\n";
        Files.writeString(staging.resolve("pack.mcmeta"), metadata, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }

    private static Path rulePath(Path dataRoot, LightingRuleDraft draft) throws IOException {
        Path path = dataRoot.resolve(draft.id().getNamespace())
                .resolve("totem_lumen/lighting")
                .resolve(draft.id().getPath() + ".json").normalize();
        if (!path.startsWith(dataRoot)) throw new IOException("Invalid block rule path");
        return path;
    }

    private static String ruleContents(LightingRuleDraft draft) {
        JsonArray color = new JsonArray();
        color.add(draft.red() / 255.0f);
        color.add(draft.green() / 255.0f);
        color.add(draft.blue() / 255.0f);
        JsonObject rule = new JsonObject();
        rule.add("emission_color", color);
        if (draft.strength() >= 0) rule.addProperty("gameplay_strength", draft.strength());
        return rule + "\n";
    }

    private static void cleanupStaging(Path staging) throws IOException {
        try (Stream<Path> paths = Files.walk(staging)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void reload(MinecraftServer server, List<String> selected, Minecraft client,
                               Consumer<Throwable> completed) {
        server.reloadResources(selected).whenComplete((ignored, problem) ->
                client.execute(() -> completed.accept(problem)));
    }

    private static List<String> selectedIds(PackRepository repository) {
        return repository.getSelectedPacks().stream().map(Pack::getId)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static boolean managed(String packId) {
        return DEFAULT_PACK_ID.equals(packId)
                || TEST_PACK_ID.equals(packId)
                || canEditExisting(packId);
    }
}
