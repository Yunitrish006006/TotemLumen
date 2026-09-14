package dev.totem.lumen.scene;

import java.util.HashSet;
import java.util.Set;

/**
 * Minimal CPU-side scene index for P1.
 *
 * <p>P2 replaces chunk-only presence with compact section/material storage while preserving this
 * Minecraft-independent boundary. Dirty sections are tracked separately so later GPU uploads can
 * rebuild only the affected section data.</p>
 */
public final class RayScene {
    private final Set<ChunkKey> loadedChunks = new HashSet<>();
    private final Set<SectionKey> dirtySections = new HashSet<>();
    private String activeDimension;
    private long lastAppliedSequence;
    private long blockChangeCount;

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
                dirtySections.clear();
                blockChangeCount = 0;
            }
            case SceneUpdate.LevelCleared ignored -> {
                activeDimension = null;
                loadedChunks.clear();
                dirtySections.clear();
                blockChangeCount = 0;
            }
            case SceneUpdate.ChunkLoaded loaded -> {
                if (loaded.dimensionId().equals(activeDimension)) {
                    loadedChunks.add(new ChunkKey(loaded.dimensionId(), loaded.chunkX(), loaded.chunkZ()));
                }
            }
            case SceneUpdate.ChunkUnloaded unloaded -> {
                loadedChunks.remove(new ChunkKey(unloaded.dimensionId(), unloaded.chunkX(), unloaded.chunkZ()));
                dirtySections.removeIf(section ->
                        section.dimensionId().equals(unloaded.dimensionId())
                                && section.x() == unloaded.chunkX()
                                && section.z() == unloaded.chunkZ()
                );
            }
            case SceneUpdate.BlockChanged changed -> {
                if (changed.dimensionId().equals(activeDimension)) {
                    blockChangeCount++;
                    markDirtyHalo(changed);
                }
            }
        }

        lastAppliedSequence = update.sequence();
    }

    /**
     * Minecraft's LevelExtractor dirties a 3x3x3 block halo around one changed block. The halo
     * normally maps to one section, but a block on a section edge/corner can affect up to eight.
     * Mirror that section coverage now so P2/P3 do not miss neighbour-dependent geometry.
     */
    private void markDirtyHalo(SceneUpdate.BlockChanged changed) {
        int minSectionX = Math.floorDiv(changed.blockX() - 1, 16);
        int maxSectionX = Math.floorDiv(changed.blockX() + 1, 16);
        int minSectionY = Math.floorDiv(changed.blockY() - 1, 16);
        int maxSectionY = Math.floorDiv(changed.blockY() + 1, 16);
        int minSectionZ = Math.floorDiv(changed.blockZ() - 1, 16);
        int maxSectionZ = Math.floorDiv(changed.blockZ() + 1, 16);

        for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
            for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
                    dirtySections.add(new SectionKey(changed.dimensionId(), sectionX, sectionY, sectionZ));
                }
            }
        }
    }

    public String activeDimension() {
        return activeDimension;
    }

    public int loadedChunkCount() {
        return loadedChunks.size();
    }

    public int dirtySectionCount() {
        return dirtySections.size();
    }

    public boolean isSectionDirty(SectionKey key) {
        return dirtySections.contains(key);
    }

    public long blockChangeCount() {
        return blockChangeCount;
    }

    public long lastAppliedSequence() {
        return lastAppliedSequence;
    }
}
