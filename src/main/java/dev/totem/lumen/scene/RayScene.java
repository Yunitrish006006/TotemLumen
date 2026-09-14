package dev.totem.lumen.scene;

import java.util.HashSet;
import java.util.Set;

/**
 * Minimal CPU-side scene index for P1.
 *
 * <p>P2 will replace the chunk-only presence set with compact section/material storage while
 * preserving this Minecraft-independent boundary.</p>
 */
public final class RayScene {
    private final Set<ChunkKey> loadedChunks = new HashSet<>();
    private String activeDimension;
    private long lastAppliedSequence;

    public int applyPending(SceneUpdateQueue queue) {
        int applied = 0;
        SceneUpdate update;
        while ((update = queue.poll()) != null) {
            apply(update);
            applied++;
        }
        return applied;
    }

    public void apply(SceneUpdate update) {
        if (update.sequence() <= lastAppliedSequence) {
            return;
        }

        switch (update) {
            case SceneUpdate.LevelChanged changed -> {
                activeDimension = changed.dimensionId();
                loadedChunks.clear();
            }
            case SceneUpdate.LevelCleared ignored -> {
                activeDimension = null;
                loadedChunks.clear();
            }
            case SceneUpdate.ChunkLoaded loaded -> {
                if (loaded.dimensionId().equals(activeDimension)) {
                    loadedChunks.add(new ChunkKey(loaded.dimensionId(), loaded.chunkX(), loaded.chunkZ()));
                }
            }
            case SceneUpdate.ChunkUnloaded unloaded ->
                    loadedChunks.remove(new ChunkKey(unloaded.dimensionId(), unloaded.chunkX(), unloaded.chunkZ()));
        }

        lastAppliedSequence = update.sequence();
    }

    public String activeDimension() {
        return activeDimension;
    }

    public int loadedChunkCount() {
        return loadedChunks.size();
    }

    public long lastAppliedSequence() {
        return lastAppliedSequence;
    }
}
