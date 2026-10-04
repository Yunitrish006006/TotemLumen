package dev.totem.lumen.render;

import dev.totem.lumen.gpu.GpuSceneAbi;

/** Lossless texture layout for staged raster material IDs and the existing MaterialDefinition ABI. */
public final class RasterMaterialGpuLayout {
    public static final int WORDS_PER_MATERIAL = GpuSceneAbi.MATERIAL_STRIDE_BYTES / Integer.BYTES;
    public static final int MATERIALS_PER_ROW = 256;
    public static final int LUT_WIDTH = MATERIALS_PER_ROW * WORDS_PER_MATERIAL;

    static {
        if (GpuSceneAbi.MATERIAL_STRIDE_BYTES % Integer.BYTES != 0 || WORDS_PER_MATERIAL != 16) {
            throw new IllegalStateException("Unexpected material ABI stride: " + GpuSceneAbi.MATERIAL_STRIDE_BYTES);
        }
    }

    private RasterMaterialGpuLayout() { }

    /** ABGR integer for an RGBA8_UNORM texel: low byte in R, high byte in G, A=255. */
    public static int materialIdTexel(int materialId) {
        if (materialId < 0 || materialId > RasterMaterialRegistry.MAX_MATERIAL_ID) {
            throw new IllegalArgumentException("materialId out of range: " + materialId);
        }
        return 0xFF000000 | (materialId & 0xFF) | (((materialId >>> 8) & 0xFF) << 8);
    }

    public static int materialIdFromTexel(int abgr) {
        return (abgr & 0xFF) | (((abgr >>> 8) & 0xFF) << 8);
    }

    public static int lutX(int materialId, int word) {
        if (materialId < 0 || materialId > RasterMaterialRegistry.MAX_MATERIAL_ID) {
            throw new IllegalArgumentException("materialId out of range: " + materialId);
        }
        if (word < 0 || word >= WORDS_PER_MATERIAL) {
            throw new IllegalArgumentException("material word out of range: " + word);
        }
        return (materialId & 0xFF) * WORDS_PER_MATERIAL + word;
    }

    public static int lutY(int materialId) {
        if (materialId < 0 || materialId > RasterMaterialRegistry.MAX_MATERIAL_ID) {
            throw new IllegalArgumentException("materialId out of range: " + materialId);
        }
        return materialId >>> 8;
    }

    public static int requiredRows(int maximumMaterialId) {
        if (maximumMaterialId < 0 || maximumMaterialId > RasterMaterialRegistry.MAX_MATERIAL_ID) {
            throw new IllegalArgumentException("materialId out of range: " + maximumMaterialId);
        }
        return Math.max(1, lutY(maximumMaterialId) + 1);
    }
}
