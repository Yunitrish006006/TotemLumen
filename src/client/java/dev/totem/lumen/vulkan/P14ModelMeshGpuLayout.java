package dev.totem.lumen.vulkan;

import dev.totem.lumen.geometry.BlockModelMeshRegistry;

/** Shared CPU/GLSL layout constants for P14C generic block-model geometry. */
public final class P14ModelMeshGpuLayout {
    public static final int MESH_DESCRIPTOR_COUNT = BlockModelMeshRegistry.MAX_MESH_ID + 1;
    public static final int MESH_DESCRIPTOR_WORDS_PER_RECORD = 2;
    public static final int MESH_DESCRIPTOR_WORDS = MESH_DESCRIPTOR_COUNT * MESH_DESCRIPTOR_WORDS_PER_RECORD;
    public static final int QUAD_WORDS_PER_RECORD = BlockModelMeshRegistry.FLOATS_PER_QUAD;
    public static final int QUAD_POOL_WORDS = BlockModelMeshRegistry.MAX_QUADS * QUAD_WORDS_PER_RECORD;
    public static final int MAX_STORAGE_WORDS = MESH_DESCRIPTOR_WORDS + QUAD_POOL_WORDS;
    public static final long MAX_STORAGE_BYTES = (long) MAX_STORAGE_WORDS * Integer.BYTES;

    private P14ModelMeshGpuLayout() {
    }
}
