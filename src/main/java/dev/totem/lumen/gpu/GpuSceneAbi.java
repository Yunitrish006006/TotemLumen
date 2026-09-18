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
     * std430-friendly material record:
     * <pre>
     * uint flags;
     * uint emissionLevel;
     * float roughness;
     * float metallic;
     * float opacity;
     * float ior;
     * float emissionR;
     * float emissionG;
     * float emissionB;
     * float transmissionR;
     * float transmissionG;
     * float transmissionB;
     * float lightRadiusScale;
     * float lightIntensityScale;
     * float reflectionScale;
     * uint lightEmitterAnchor;
     * </pre>
     */
    public static final int MATERIAL_STRIDE_BYTES = 64;

    public static final int MATERIAL_FLAGS_OFFSET = 0;
    public static final int MATERIAL_EMISSION_OFFSET = 4;
    public static final int MATERIAL_ROUGHNESS_OFFSET = 8;
    public static final int MATERIAL_METALLIC_OFFSET = 12;
    public static final int MATERIAL_OPACITY_OFFSET = 16;
    public static final int MATERIAL_IOR_OFFSET = 20;
    public static final int MATERIAL_EMISSION_R_OFFSET = 24;
    public static final int MATERIAL_EMISSION_G_OFFSET = 28;
    public static final int MATERIAL_EMISSION_B_OFFSET = 32;
    public static final int MATERIAL_TRANSMISSION_R_OFFSET = 36;
    public static final int MATERIAL_TRANSMISSION_G_OFFSET = 40;
    public static final int MATERIAL_TRANSMISSION_B_OFFSET = 44;
    public static final int MATERIAL_LIGHT_RADIUS_SCALE_OFFSET = 48;
    public static final int MATERIAL_LIGHT_INTENSITY_SCALE_OFFSET = 52;
    public static final int MATERIAL_REFLECTION_SCALE_OFFSET = 56;
    public static final int MATERIAL_LIGHT_EMITTER_ANCHOR_OFFSET = 60;

    private GpuSceneAbi() {
    }
}
