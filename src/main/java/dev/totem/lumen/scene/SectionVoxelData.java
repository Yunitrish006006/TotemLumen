package dev.totem.lumen.scene;

import java.util.Arrays;

/**
 * Minecraft-independent 16x16x16 material-ID volume.
 *
 * <p>Layout is Y-major by 256, then Z by 16, then X. This makes the local-coordinate index
 * {@code (y << 8) | (z << 4) | x}, matching the natural 4-bit coordinate packing.</p>
 */
public final class SectionVoxelData {
    public static final int SIZE = 16;
    public static final int VOXEL_COUNT = SIZE * SIZE * SIZE;

    private final int[] materialIds;

    public SectionVoxelData(int[] materialIds) {
        if (materialIds.length != VOXEL_COUNT) {
            throw new IllegalArgumentException("Expected " + VOXEL_COUNT + " material IDs");
        }
        this.materialIds = materialIds.clone();
    }

    public static SectionVoxelData empty() {
        return new SectionVoxelData(new int[VOXEL_COUNT]);
    }

    public int materialId(int localX, int localY, int localZ) {
        return materialIds[index(localX, localY, localZ)];
    }

    public int[] copyMaterialIds() {
        return materialIds.clone();
    }

    public boolean isAllAir() {
        return Arrays.stream(materialIds).allMatch(id -> id == 0);
    }

    public static int index(int localX, int localY, int localZ) {
        checkLocal(localX);
        checkLocal(localY);
        checkLocal(localZ);
        return (localY << 8) | (localZ << 4) | localX;
    }

    private static void checkLocal(int value) {
        if (value < 0 || value >= SIZE) {
            throw new IndexOutOfBoundsException("Section-local coordinate must be in [0, 15]: " + value);
        }
    }
}
