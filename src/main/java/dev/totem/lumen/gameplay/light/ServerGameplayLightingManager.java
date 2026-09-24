package dev.totem.lumen.gameplay.light;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Owns one authoritative gameplay-light engine per loaded server dimension. */
public final class ServerGameplayLightingManager {
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
        int remainingWork = DEFAULT_SERVER_WORK_BUDGET;
        int remainingEngines = active.size();
        for (ServerGameplayLightEngine candidate : active) {
            if (remainingWork <= 0) {
                break;
            }
            long elapsed = System.nanoTime() - start;
            long remainingTime = DEFAULT_SERVER_TIME_BUDGET_NANOS - elapsed;
            if (remainingTime <= 0L) {
                break;
            }

            int workShare = Math.max(1, remainingWork / remainingEngines);
            long timeShare = Math.max(1L, remainingTime / remainingEngines);
            int consumed = candidate.tick(workShare, timeShare);
            // The RGB terrain field is recomputed locally by each client. Drain the server
            // change queue so it remains bounded; the server engine is still required by
            // gameplay consumers such as spawn-light logic.
            candidate.drainChangedSections();
            remainingWork -= consumed;
            remainingEngines--;
        }
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
