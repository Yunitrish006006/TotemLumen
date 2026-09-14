package dev.totem.lumen.scene;

/**
 * Immutable ownership boundary for one CPU-side section snapshot.
 */
public record SectionSnapshot(SectionKey key, long revision, SectionVoxelData voxels) {
    public SectionSnapshot {
        if (key == null || voxels == null) {
            throw new IllegalArgumentException("key and voxels are required");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
    }
}
