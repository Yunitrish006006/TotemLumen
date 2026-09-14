package dev.totem.lumen.gpu;

/**
 * CPU/GPU binary ABI shared by section/material upload code and future SPIR-V compute shaders.
 * Changes to these values are format changes and must be made deliberately.
 */
public final class GpuSceneAbi {
    public static final int MATERIAL_ID_BYTES = Integer.BYTES;
    public static final int SECTION_VOXELS = 16 * 16 * 16;
    public static final int SECTION_VOXEL_BYTES = SECTION_VOXELS * MATERIAL_ID_BYTES;

    /**
     * std430-friendly baseline material record:
     * <pre>
     * uint flags;
     * uint emissionLevel;
     * float roughness;
     * float metallic;
     * float opacity;
     * float ior;
     * uint reserved0;
     * uint reserved1;
     * </pre>
     */
    public static final int MATERIAL_STRIDE_BYTES = 32;

    public static final int MATERIAL_FLAGS_OFFSET = 0;
    public static final int MATERIAL_EMISSION_OFFSET = 4;
    public static final int MATERIAL_ROUGHNESS_OFFSET = 8;
    public static final int MATERIAL_METALLIC_OFFSET = 12;
    public static final int MATERIAL_OPACITY_OFFSET = 16;
    public static final int MATERIAL_IOR_OFFSET = 20;

    private GpuSceneAbi() {
    }
}
