package dev.totem.lumen.config;

import dev.totem.lumen.TotemLumen;
import dev.totem.lumen.world.EffectiveLightingRules;
import dev.totem.lumen.world.LightingWorldRulesReloadListener;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Server lifecycle owner for the operator config; never reads disk from a tick. */
public final class ServerLightingConfigStore {
    private static volatile ServerLightingConfig current = ServerLightingConfig.DEFAULT;
    private static long revision;

    private ServerLightingConfigStore() {
    }

    public static ServerLightingConfig current() {
        return current;
    }

    public static long revision() {
        return revision;
    }

    public static void loadInitial() {
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            if (Files.notExists(path)) {
                try {
                    Files.writeString(path, ServerLightingConfig.DEFAULT_JSON, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW);
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // Another instance created it between the existence check and write.
                }
            }
            ServerLightingConfig candidate = read(path);
            EffectiveLightingRules.Snapshot effective = EffectiveLightingRules.compose(
                    LightingWorldRulesReloadListener.currentRules(), candidate,
                    LightingWorldRulesReloadListener.currentDefaultIds(),
                    LightingWorldRulesReloadListener.defaultPackActive());
            current = candidate;
            EffectiveLightingRules.install(effective);
            revision++;
            TotemLumen.LOGGER.info("Server lighting config loaded: revision={}, overrides={}",
                    revision, current.blockOverrides().size());
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Invalid Totem Lumen server config at " + path, failure);
        }
    }

    /** Parses fully before replacing the live snapshot; a failed reload keeps the prior value. */
    public static boolean reload() throws IOException {
        ServerLightingConfig candidate = read(path());
        if (candidate.equals(current)) return false;
        EffectiveLightingRules.Snapshot effective = EffectiveLightingRules.compose(
                LightingWorldRulesReloadListener.currentRules(), candidate,
                LightingWorldRulesReloadListener.currentDefaultIds(),
                LightingWorldRulesReloadListener.defaultPackActive());
        current = candidate;
        EffectiveLightingRules.install(effective);
        revision++;
        return true;
    }

    public static void reset() {
        current = ServerLightingConfig.DEFAULT;
        revision = 0L;
    }

    private static ServerLightingConfig read(Path path) throws IOException {
        if (Files.size(path) > 262_144L) {
            throw new IllegalArgumentException("server lighting config exceeds 256 KiB");
        }
        return ServerLightingConfig.parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("totem-lumen-server.json");
    }
}
