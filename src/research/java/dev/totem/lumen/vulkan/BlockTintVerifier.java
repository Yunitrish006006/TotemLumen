package dev.totem.lumen.vulkan;

import dev.totem.lumen.geometry.BlockModelMeshRegistry;
import dev.totem.lumen.geometry.QuadSurface;
import dev.totem.lumen.integration.MinecraftBlockModelMeshResolver;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** No device/world required: exercises production mesh packing and cube UV canonicalization. */
public final class BlockTintVerifier {
    public static void main(String[] args) throws Exception {
        float[] quad = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};
        QuadSurface green = new QuadSurface("test:tinted", 0, 0, 1, 0, 1, 1, 0, 1, 0x80C040);
        QuadSurface red = new QuadSurface("test:tinted", 0, 0, 1, 0, 1, 1, 0, 1, 0xC04020);
        int first = BlockModelMeshRegistry.register(quad, new QuadSurface[]{green});
        int second = BlockModelMeshRegistry.register(quad, new QuadSurface[]{red});
        require(first > 0 && second > 0 && first != second, "Tint must participate in mesh identity");
        require(first == BlockModelMeshRegistry.register(quad, new QuadSurface[]{green}), "Equal tint dedupe");
        int header = 128;
        ByteBuffer buffer = ByteBuffer.allocate((header + P14ModelMeshGpuLayout.MAX_STORAGE_WORDS + 1) * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(3 * 4, header); // no pixels, model tail begins at header
        buffer.putInt(buffer.capacity() - 4, 0x12345678);
        P14ModelMeshGpuUploader.pack(buffer);
        int pool = header + P14ModelMeshGpuLayout.MESH_DESCRIPTOR_WORDS;
        for (var mesh : BlockModelMeshRegistry.snapshot().meshes()) {
            int word = pool + mesh.firstQuad() * P14ModelMeshGpuLayout.QUAD_WORDS_PER_RECORD;
            require(buffer.getInt((word + P14ModelMeshGpuLayout.QUAD_TINT_WORD) * 4)
                    == mesh.surfaces()[0].tintRgb(), "Packed mesh tint differs");
            require(buffer.getFloat((word + P14ModelMeshGpuLayout.QUAD_UV_BASE_WORD + 2) * 4) == 1,
                    "Tint corrupts neighboring UV");
            require(buffer.getInt((word + P14ModelMeshGpuLayout.QUAD_TEXTURE_HANDLE_WORD) * 4) > 0,
                    "Tint corrupts texture handle");
        }
        require(buffer.getInt(buffer.capacity() - 4) == 0x12345678, "Tail overflow");
        var canonicalize = MinecraftBlockModelMeshResolver.class.getDeclaredMethod(
                "canonicalizeCubeFaceSurface", float[].class, int.class, int.class, QuadSurface.class);
        canonicalize.setAccessible(true);
        QuadSurface canonical = (QuadSurface) canonicalize.invoke(null, quad, 0, 2, green);
        require(canonical.tintRgb() == green.tintRgb(), "Cube canonicalization loses tint");
        System.out.println("Block tint verification PASS: mesh identity/dedupe, production GPU pack, UV/handle isolation, cube tint retention");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
