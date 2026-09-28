package dev.totem.lumen.gameplay.light;

import dev.totem.lumen.config.ServerLightingConfig;
import dev.totem.lumen.config.ServerLightingConfigStore;
import dev.totem.lumen.network.GameplayLightSectionsPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Owns one authoritative gameplay-light engine per loaded server dimension. */
public final class ServerGameplayLightingManager {
    private static final int NETWORK_SECTIONS_PER_PACKET = 24;
    public static final int DEFAULT_SERVER_WORK_BUDGET = 20_000;
    public static final long DEFAULT_SERVER_TIME_BUDGET_NANOS = 2_000_000L;

    private static final Map<ServerLevel, ServerGameplayLightEngine> ENGINES = new IdentityHashMap<>();

    private ServerGameplayLightingManager() {
    }

    public static ServerGameplayLightEngine engine(ServerLevel level) {
        return ENGINES.computeIfAbsent(level, ServerGameplayLightEngine::new);
    }

    public static ServerGameplayLightEngine existingEngine(ServerLevel level) {
        return ENGINES.get(level);
    }

    public static void onLevelLoaded(ServerLevel level) {
        engine(level);
    }

    public static void onLevelUnloaded(ServerLevel level) {
        ServerGameplayLightEngine removed = ENGINES.remove(level);
        if (removed != null) {
            removed.clear();
        }
    }

    public static void onChunkLoaded(ServerLevel level, LevelChunk chunk) {
        engine(level).onChunkLoaded(chunk);
    }

    public static void onChunkUnloaded(ServerLevel level, LevelChunk chunk) {
        ServerGameplayLightEngine existing = ENGINES.get(level);
        if (existing != null) {
            existing.onChunkUnloaded(chunk);
        }
    }

    public static void onBlockChanged(
            ServerLevel level,
            BlockPos pos,
            BlockState oldState,
            BlockState newState
    ) {
        engine(level).onBlockChanged(pos, oldState, newState);
    }

    /**
     * Spend one global budget across all dimensions with pending lighting work. This keeps the
     * 2 ms ceiling a server-wide ceiling rather than multiplying it by the number of dimensions.
     */
    public static void tick(MinecraftServer server) {
        List<ServerGameplayLightEngine> active = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            ServerGameplayLightEngine candidate = engine(level);
            if (candidate.hasPendingWork()) {
                active.add(candidate);
            }
        }
        if (active.isEmpty()) {
            return;
        }

        long start = System.nanoTime();
        ServerLightingConfig config = ServerLightingConfigStore.current();
        int remainingWork = config.workBudget();
        int remainingEngines = active.size();
        for (ServerGameplayLightEngine candidate : active) {
            if (remainingWork <= 0) {
                break;
            }
            long elapsed = System.nanoTime() - start;
            long remainingTime = config.timeBudgetNanos() - elapsed;
            if (remainingTime <= 0L) {
                break;
            }

            int workShare = Math.max(1, remainingWork / remainingEngines);
            long timeShare = Math.max(1L, remainingTime / remainingEngines);
            int consumed = candidate.tick(workShare, timeShare);
            List<ServerGameplayLightEngine.SectionSnapshot> changed = candidate.drainChangedSections();
            if (!changed.isEmpty()) {
                broadcastStableSections(candidate, changed);
            }
            remainingWork -= consumed;
            remainingEngines--;
        }
    }

    public static void sendFullSync(ServerPlayer player) {
        ServerLevel level = player.level();
        ServerGameplayLightEngine engine = engine(level);
        List<ServerGameplayLightEngine.SectionSnapshot> sections = engine.snapshotSections();
        long revision = engine.fieldRevision();
        if (sections.isEmpty()) {
            sendPacket(player, level, revision, List.of(), true);
            return;
        }
        for (int offset = 0; offset < sections.size(); offset += NETWORK_SECTIONS_PER_PACKET) {
            int end = Math.min(sections.size(), offset + NETWORK_SECTIONS_PER_PACKET);
            sendPacket(player, level, revision, sections.subList(offset, end), offset == 0);
        }
    }

    private static void broadcastStableSections(
            ServerGameplayLightEngine engine,
            List<ServerGameplayLightEngine.SectionSnapshot> sections
    ) {
        ServerLevel level = engine.level();
        long revision = engine.fieldRevision();
        for (int offset = 0; offset < sections.size(); offset += NETWORK_SECTIONS_PER_PACKET) {
            int end = Math.min(sections.size(), offset + NETWORK_SECTIONS_PER_PACKET);
            GameplayLightSectionsPayload payload = payload(
                    level,
                    revision,
                    sections.subList(offset, end),
                    false
            );
            for (ServerPlayer player : level.players()) {
                if (ServerPlayNetworking.canSend(player, GameplayLightSectionsPayload.TYPE)) {
                    ServerPlayNetworking.send(player, payload);
                }
            }
        }
    }

    private static void sendPacket(
            ServerPlayer player,
            ServerLevel level,
            long revision,
            List<ServerGameplayLightEngine.SectionSnapshot> sections,
            boolean fullSync
    ) {
        if (!ServerPlayNetworking.canSend(player, GameplayLightSectionsPayload.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(player, payload(level, revision, sections, fullSync));
    }

    private static GameplayLightSectionsPayload payload(
            ServerLevel level,
            long revision,
            List<ServerGameplayLightEngine.SectionSnapshot> sections,
            boolean fullSync
    ) {
        return new GameplayLightSectionsPayload(
                level.dimension().identifier(),
                revision,
                sections.stream()
                        .map(section -> new GameplayLightSectionsPayload.Section(
                                section.x(), section.y(), section.z(), section.values()
                        ))
                        .toList(),
                fullSync
        );
    }

    public static void refreshWorldRules(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            engine(level).refreshWorldRules();
        }
    }

    public static void clearAll() {
        for (ServerGameplayLightEngine engine : ENGINES.values()) {
            engine.clear();
        }
        ENGINES.clear();
    }
}
