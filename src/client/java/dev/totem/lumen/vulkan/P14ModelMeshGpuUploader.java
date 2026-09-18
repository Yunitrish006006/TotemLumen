package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.geometry.BlockModelMeshRegistry;
import dev.totem.lumen.geometry.QuadSurface;
import dev.totem.lumen.material.PbrTextureHandleRegistry;

import java.nio.ByteBuffer;

/** Packs the shared P14C/P14D model registry into the scene-buffer tail after the pixel target. */
public final class P14ModelMeshGpuUploader {
    private static volatile long lastBaseByteOffset = -1L;
    private static volatile long lastCopyBytes;
    private static long lastPackedRevision = Long.MIN_VALUE;
    private static boolean copyPending;
    private static int lastLoggedMeshCount = -1;
    private static int lastLoggedDynamicMeshCount = -1;
    private static int lastLoggedQuadCount = -1;

    private P14ModelMeshGpuUploader() {
    }

    /** Force-packs the current registry, used when the owning scene resources/static data rebuild. */
    public static synchronized void pack(ByteBuffer buffer) {
        packSnapshot(buffer, BlockModelMeshRegistry.snapshot());
    }

    /**
     * Packs only when a static or mutable block-entity mesh changed. This allows animation to update
     * the mesh tail without re-uploading all section voxels.
     */
    public static synchronized boolean packIfDirty(ByteBuffer buffer) {
        BlockModelMeshRegistry.Snapshot snapshot = BlockModelMeshRegistry.snapshot();
        if (snapshot.revision() == lastPackedRevision) return false;
        packSnapshot(buffer, snapshot);
        return true;
    }

    private static void packSnapshot(ByteBuffer buffer, BlockModelMeshRegistry.Snapshot snapshot) {
        int pixelBaseWord = buffer.getInt(3 * Integer.BYTES);
        int capacityWidth = buffer.getInt(51 * Integer.BYTES);
        int capacityHeight = buffer.getInt(52 * Integer.BYTES);
        int pixelCount = Math.multiplyExact(capacityWidth, capacityHeight);
        int modelBaseWord = Math.addExact(pixelBaseWord, pixelCount);
        int descriptorBaseWord = modelBaseWord;
        int quadBaseWord = Math.addExact(descriptorBaseWord, P14ModelMeshGpuLayout.MESH_DESCRIPTOR_WORDS);

        int usedQuadWords = Math.multiplyExact(
                snapshot.totalQuads(),
                P14ModelMeshGpuLayout.QUAD_WORDS_PER_RECORD
        );
        int usedWords = Math.addExact(P14ModelMeshGpuLayout.MESH_DESCRIPTOR_WORDS, usedQuadWords);
        long endBytes = (long) (modelBaseWord + usedWords) * Integer.BYTES;
        if (endBytes > buffer.capacity()) {
            throw new IllegalStateException(
                    "P14 model storage exceeds scene upload buffer: required=" + endBytes
                            + ", capacity=" + buffer.capacity()
            );
        }

        // Every descriptor is cleared because released/reused P14D ids can leave holes.
        for (int word = 0; word < P14ModelMeshGpuLayout.MESH_DESCRIPTOR_WORDS; word++) {
            putWord(buffer, descriptorBaseWord + word, 0);
        }

        for (BlockModelMeshRegistry.Mesh mesh : snapshot.meshes()) {
            int descriptor = descriptorBaseWord
                    + mesh.id() * P14ModelMeshGpuLayout.MESH_DESCRIPTOR_WORDS_PER_RECORD;
            putWord(buffer, descriptor, mesh.firstQuad());
            putWord(buffer, descriptor + 1, mesh.quadCount());

            float[] positions = mesh.positions();
            QuadSurface[] surfaces = mesh.surfaces();
            for (int quad = 0; quad < mesh.quadCount(); quad++) {
                int quadWord = quadBaseWord
                        + (mesh.firstQuad() + quad)
                        * P14ModelMeshGpuLayout.QUAD_WORDS_PER_RECORD;
                int positionOffset = quad * BlockModelMeshRegistry.FLOATS_PER_QUAD;
                for (int word = 0; word < P14ModelMeshGpuLayout.QUAD_POSITION_WORDS; word++) {
                    putWord(
                            buffer,
                            quadWord + word,
                            Float.floatToRawIntBits(positions[positionOffset + word])
                    );
                }

                QuadSurface surface = surfaces[quad];
                int uvWord = quadWord + P14ModelMeshGpuLayout.QUAD_UV_BASE_WORD;
                for (int vertex = 0; vertex < 4; vertex++) {
                    putWord(
                            buffer,
                            uvWord + vertex * 2,
                            Float.floatToRawIntBits(surface.u(vertex))
                    );
                    putWord(
                            buffer,
                            uvWord + vertex * 2 + 1,
                            Float.floatToRawIntBits(surface.v(vertex))
                    );
                }

                int textureHandle = surface.textured()
                        ? PbrTextureHandleRegistry.handleFor(surface.spriteId())
                        : 0;
                if (textureHandle < 0) textureHandle = 0;
                putWord(
                        buffer,
                        quadWord + P14ModelMeshGpuLayout.QUAD_TEXTURE_HANDLE_WORD,
                        textureHandle
                );
            }
        }

        lastBaseByteOffset = (long) modelBaseWord * Integer.BYTES;
        lastCopyBytes = (long) usedWords * Integer.BYTES;
        lastPackedRevision = snapshot.revision();
        copyPending = true;

        int dynamicMeshes = BlockModelMeshRegistry.dynamicMeshCount();
        if (lastLoggedMeshCount != snapshot.meshes().size()
                || lastLoggedDynamicMeshCount != dynamicMeshes
                || lastLoggedQuadCount != snapshot.totalQuads()) {
            lastLoggedMeshCount = snapshot.meshes().size();
            lastLoggedDynamicMeshCount = dynamicMeshes;
            lastLoggedQuadCount = snapshot.totalQuads();
            TotemLumenClient.LOGGER.info(
                    "P14 model GPU registry: meshes={}, dynamicMeshes={}, quads={}, bytes={}, maxBytes={}",
                    snapshot.meshes().size(),
                    dynamicMeshes,
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

    public static synchronized boolean consumeCopyPending() {
        boolean pending = copyPending;
        copyPending = false;
        return pending;
    }

    private static void putWord(ByteBuffer buffer, int wordIndex, int value) {
        buffer.putInt(wordIndex * Integer.BYTES, value);
    }
}
