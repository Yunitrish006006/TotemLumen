package dev.totem.lumen.scene;

import java.util.Arrays;

/**
 * Minecraft-independent 16x16x16 packed voxel-word volume.
 *
 * <p>Layout is Y-major by 256, then Z by 16, then X. This makes the local-coordinate index
 * {@code (y << 8) | (z << 4) | x}, matching the natural 4-bit coordinate packing.</p>
 *
 * <p>Each 32-bit voxel word reserves the low 16 bits for the stable material ID and the high
 * 16 bits for a block-local geometry code. Geometry code zero is the legacy/full-cube fast path,
 * so old callers that pass plain material IDs remain binary-compatible.</p>
 */
public final class SectionVoxelData {
    public static final int SIZE = 16;
    public static final int VOXEL_COUNT = SIZE * SIZE * SIZE;
    public static final int MATERIAL_MASK = 0xFFFF;
    public static final int GEOMETRY_SHIFT = 16;
    public static final int GEOMETRY_MASK = 0xFFFF;

    private final int[] voxelWords;

    public SectionVoxelData(int[] voxelWords) {
        if (voxelWords.length != VOXEL_COUNT) {
            throw new IllegalArgumentException("Expected " + VOXEL_COUNT + " voxel words");
        }
        this.voxelWords = voxelWords.clone();
    }

    public static SectionVoxelData empty() {
        return new SectionVoxelData(new int[VOXEL_COUNT]);
    }

    public static int packVoxelWord(int materialId, int geometryCode) {
        if ((materialId & ~MATERIAL_MASK) != 0) {
            throw new IllegalArgumentException("materialId must fit in 16 bits: " + materialId);
        }
        if ((geometryCode & ~GEOMETRY_MASK) != 0) {
            throw new IllegalArgumentException("geometryCode must fit in 16 bits: " + geometryCode);
        }
        return (geometryCode << GEOMETRY_SHIFT) | (materialId & MATERIAL_MASK);
    }

    public static int unpackMaterialId(int voxelWord) {
        return voxelWord & MATERIAL_MASK;
    }

    public static int unpackGeometryCode(int voxelWord) {
        return (voxelWord >>> GEOMETRY_SHIFT) & GEOMETRY_MASK;
    }

    public int materialId(int localX, int localY, int localZ) {
        return unpackMaterialId(voxelWords[index(localX, localY, localZ)]);
    }

    public int geometryCode(int localX, int localY, int localZ) {
        return unpackGeometryCode(voxelWords[index(localX, localY, localZ)]);
    }

    /**
     * Returns the packed GPU voxel words. The historical method name is kept so the established
     * P5-P13 upload path can adopt P14 geometry metadata without widening the scene buffer ABI.
     */
    public int[] copyMaterialIds() {
        return voxelWords.clone();
    }

    /** Returns a material-only copy for CPU algorithms that must not observe geometry bits. */
    public int[] copyRawMaterialIds() {
        int[] materialIds = new int[voxelWords.length];
        for (int index = 0; index < voxelWords.length; index++) {
            materialIds[index] = unpackMaterialId(voxelWords[index]);
        }
        return materialIds;
    }

    public boolean isAllAir() {
        return Arrays.stream(voxelWords).allMatch(word -> unpackMaterialId(word) == 0);
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
