package dev.totem.lumen.scene;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Totem Lumen-owned CPU scene. No Minecraft world objects are retained here.
 */
public final class RayScene {
    private final Set<ChunkKey> loadedChunks = new HashSet<>();
    private final Set<SectionKey> dirtySections = new HashSet<>();
    private final Map<SectionKey, SectionSnapshot> sections = new HashMap<>();
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
                sections.clear();
                blockChangeCount = 0;
            }
            case SceneUpdate.LevelCleared ignored -> {
                activeDimension = null;
                loadedChunks.clear();
                dirtySections.clear();
                sections.clear();
                blockChangeCount = 0;
            }
            case SceneUpdate.ChunkLoaded loaded -> {
                if (loaded.dimensionId().equals(activeDimension)) {
                    loadedChunks.add(new ChunkKey(loaded.dimensionId(), loaded.chunkX(), loaded.chunkZ()));
                }
            }
            case SceneUpdate.ChunkUnloaded unloaded -> removeChunk(
                    unloaded.dimensionId(), unloaded.chunkX(), unloaded.chunkZ()
            );
            case SceneUpdate.BlockChanged changed -> {
                if (changed.dimensionId().equals(activeDimension)) {
                    blockChangeCount++;
                    SectionCoordinates.forDirtyHalo(
                            changed.dimensionId(),
                            changed.blockX(),
                            changed.blockY(),
                            changed.blockZ(),
                            dirtySections::add
                    );
                }
            }
            case SceneUpdate.SectionRebuilt rebuilt -> {
                SectionSnapshot snapshot = rebuilt.snapshot();
                SectionKey key = snapshot.key();
                if (key.dimensionId().equals(activeDimension)) {
                    if (snapshot.voxels().isAllAir()) {
                        sections.remove(key);
                    } else {
                        SectionSnapshot current = sections.get(key);
                        if (current == null || snapshot.revision() >= current.revision()) {
                            sections.put(key, snapshot);
                        }
                    }
                    dirtySections.remove(key);
                }
            }
        }

        lastAppliedSequence = update.sequence();
    }

    private void removeChunk(String dimensionId, int chunkX, int chunkZ) {
        loadedChunks.remove(new ChunkKey(dimensionId, chunkX, chunkZ));
        dirtySections.removeIf(section -> inChunk(section, dimensionId, chunkX, chunkZ));
        sections.keySet().removeIf(section -> inChunk(section, dimensionId, chunkX, chunkZ));
    }

    private static boolean inChunk(SectionKey section, String dimensionId, int chunkX, int chunkZ) {
        return section.dimensionId().equals(dimensionId)
                && section.x() == chunkX
                && section.z() == chunkZ;
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

    public int populatedSectionCount() {
        return sections.size();
    }

    public SectionSnapshot section(SectionKey key) {
        return sections.get(key);
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
