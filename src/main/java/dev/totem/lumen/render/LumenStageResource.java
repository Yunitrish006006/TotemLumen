package dev.totem.lumen.render;

/** Stable logical resources exchanged between coarse Lumen render stages. */
public enum LumenStageResource {
    NATIVE_SCENE_COLOR,
    NATIVE_DEPTH,
    SURFACE,
    MATERIAL,
    VOXEL_SCENE,
    DIRECT_RADIANCE,
    INDIRECT_RADIANCE,
    REFLECTION_RADIANCE,
    TEMPORAL_RADIANCE,
    DENOISED_RADIANCE,
    FINAL_COLOR
}
