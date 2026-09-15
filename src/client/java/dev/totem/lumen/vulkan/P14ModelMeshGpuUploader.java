package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.geometry.BlockModelMeshRegistry;

import java.nio.ByteBuffer;

/** Packs the deduplicated P14C model registry into the scene-buffer tail after the pixel target. */
public final class P14ModelMeshGpuUploader {
    private static volatile long lastBaseByteOffset = -1L;
    private static volatile long lastCopyBytes;
    private static int lastLoggedMeshCount = -1;
    private static int lastLoggedQuadCount = -1;

    private P14ModelMeshGpuUploader() {
    }

    public static void pack(ByteBuffer buffer) {
        int pixelBaseWord = buffer.getInt(3 * Integer.BYTES);
        int width = buffer.getInt(4 * Integer.BYTES);
        int height = buffer.getInt(5 * Integer.BYTES);
        int pixelCount = Math.multiplyExact(width, height);
        int modelBaseWord = Math.addExact(pixelBaseWord, pixelCount);
        int descriptorBaseWord = modelBaseWord;
        int quadBaseWord = Math.addExact(descriptorBaseWord, P14ModelMeshGpuLayout.MESH_DESCRIPTOR_WORDS);

        BlockModelMeshRegistry.Snapshot snapshot = BlockModelMeshRegistry.snapshot();
        int usedQuadWords = Math.multiplyExact(
                snapshot.totalQuads(),
                P14ModelMeshGpuLayout.QUAD_WORDS_PER_RECORD
        );
        int usedWords = Math.addExact(P14ModelMeshGpuLayout.MESH_DESCRIPTOR_WORDS, usedQuadWords);
        long endBytes = (long) (modelBaseWord + usedWords) * Integer.BYTES;
        if (endBytes > buffer.capacity()) {
            throw new IllegalStateException(
                    "P14C model storage exceeds scene upload buffer: required=" + endBytes
                            + ", capacity=" + buffer.capacity()
            );
        }

        // Mesh id zero is the explicit empty-model descriptor.
        putWord(buffer, descriptorBaseWord, 0);
        putWord(buffer, descriptorBaseWord + 1, 0);

        for (BlockModelMeshRegistry.Mesh mesh : snapshot.meshes()) {
            int descriptor = descriptorBaseWord
                    + mesh.id() * P14ModelMeshGpuLayout.MESH_DESCRIPTOR_WORDS_PER_RECORD;
            putWord(buffer, descriptor, mesh.firstQuad());
            putWord(buffer, descriptor + 1, mesh.quadCount());

            int quadWord = quadBaseWord
                    + mesh.firstQuad() * P14ModelMeshGpuLayout.QUAD_WORDS_PER_RECORD;
            float[] positions = mesh.positions();
            for (float position : positions) {
                putWord(buffer, quadWord++, Float.floatToRawIntBits(position));
            }
        }

        lastBaseByteOffset = (long) modelBaseWord * Integer.BYTES;
        lastCopyBytes = (long) usedWords * Integer.BYTES;

        if (lastLoggedMeshCount != snapshot.meshes().size() || lastLoggedQuadCount != snapshot.totalQuads()) {
            lastLoggedMeshCount = snapshot.meshes().size();
            lastLoggedQuadCount = snapshot.totalQuads();
            TotemLumenClient.LOGGER.info(
                    "P14C generic model GPU registry: meshes={}, quads={}, bytes={}, maxBytes={}",
                    snapshot.meshes().size(),
                    snapshot.totalQuads(),
                    lastCopyBytes,
                    P14ModelMeshGpuLayout.MAX_STORAGE_BYTES
            );
        }
    }

    public static long lastBaseByteOffset() {
        return lastBaseByteOffset;
    }

    public static long lastCopyBytes() {
        return lastCopyBytes;
    }

    private static void putWord(ByteBuffer buffer, int wordIndex, int value) {
        buffer.putInt(wordIndex * Integer.BYTES, value);
    }
}
