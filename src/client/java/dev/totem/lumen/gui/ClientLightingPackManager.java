package dev.totem.lumen.gui;

import com.mojang.blaze3d.Blaze3D;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.totem.lumen.world.EffectiveLightingRules;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    /** Larger order means higher pack priority; disabled packs use -1. */
    public record PackInfo(String id, String title, boolean enabled, boolean required, int order) {}

    private ClientLightingPackManager() {}

    public static boolean canManage(Minecraft client) {
        return client.hasSingleplayerServer() && client.getSingleplayerServer() != null;
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
        if (drafts.isEmpty() || drafts.size() > 8_192) {
            completed.accept(new IllegalArgumentException("Expected 1-8192 pending block rules"));
            return;
        }
        List<LightingRuleDraft> batch = List.copyOf(drafts);
        Set<net.minecraft.resources.Identifier> ids = new HashSet<>();
        for (LightingRuleDraft draft : batch) {
            if (!draft.changed() || !ids.add(draft.id())) {
                completed.accept(new IllegalArgumentException("Duplicate or unchanged pending block rule"));
                return;
            }
        }
        server.execute(() -> {
            try {
                if (EffectiveLightingRules.current().revision() != expectedRevision) {
                    throw new IOException("Server lighting rules changed; reopen preview before saving");
                }
                for (LightingRuleDraft draft : batch) {
                    if (EffectiveLightingRules.current().origins().get(draft.id())
                            == EffectiveLightingRules.Origin.SERVER_CONFIG) {
                        throw new IOException("Server config overrides " + draft.id());
                    }
                }
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

    static void writePack(Path staging, String name, List<LightingRuleDraft> batch) throws IOException {
        Path dataRoot = staging.resolve("data");
        for (LightingRuleDraft draft : batch) {
            Path rulePath = dataRoot.resolve(draft.id().getNamespace())
                    .resolve("totem_lumen/lighting")
                    .resolve(draft.id().getPath() + ".json").normalize();
            if (!rulePath.startsWith(dataRoot)) throw new IOException("Invalid block rule path");
            Files.createDirectories(rulePath.getParent());
            JsonArray color = new JsonArray();
            color.add(draft.red() / 255.0f);
            color.add(draft.green() / 255.0f);
            color.add(draft.blue() / 255.0f);
            JsonObject rule = new JsonObject();
            rule.add("emission_color", color);
            if (draft.strength() >= 0) rule.addProperty("gameplay_strength", draft.strength());
            Files.writeString(rulePath, rule + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        }
        String metadata = "{\"pack\":{\"min_format\":121,\"max_format\":121,\"description\":\"Totem Lumen lighting: "
                + name + "\"}}\n";
        Files.writeString(staging.resolve("pack.mcmeta"), metadata, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
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
                || packId.startsWith("file/" + CUSTOM_PREFIX)
                || packId.startsWith("file:" + CUSTOM_PREFIX);
    }
}
