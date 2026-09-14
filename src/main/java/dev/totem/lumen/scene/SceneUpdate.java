package dev.totem.lumen.scene;

/**
 * Immutable Minecraft-to-renderer scene updates.
 *
 * <p>These records intentionally contain no Minecraft objects. The Vulkan side must consume
 * Totem Lumen-owned values instead of retaining mutable ClientLevel/LevelChunk/BlockPos instances.</p>
 */
public sealed interface SceneUpdate
        permits SceneUpdate.LevelChanged, SceneUpdate.LevelCleared,
        SceneUpdate.ChunkLoaded, SceneUpdate.ChunkUnloaded, SceneUpdate.BlockChanged {

    long sequence();

    record LevelChanged(long sequence, String dimensionId) implements SceneUpdate {
    }

    record LevelCleared(long sequence) implements SceneUpdate {
    }

    record ChunkLoaded(long sequence, String dimensionId, int chunkX, int chunkZ) implements SceneUpdate {
    }

    record ChunkUnloaded(long sequence, String dimensionId, int chunkX, int chunkZ) implements SceneUpdate {
    }

    record BlockChanged(
            long sequence,
            String dimensionId,
            int blockX,
            int blockY,
            int blockZ,
            int updateFlags
    ) implements SceneUpdate {
    }
}
