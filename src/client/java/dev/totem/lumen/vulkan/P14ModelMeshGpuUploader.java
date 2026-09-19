package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.geometry.BlockModelMeshRegistry;

import java.nio.ByteBuffer;

/** Packs the shared P14C/P14D model registry plus P14E cutout masks into the scene-buffer tail. */
public final class P14ModelMeshGpuUploader {
    private static volatile long lastBaseByteOffset = -1L;
    private static volatile long lastCopyBytes;
    private static long lastPackedRevision = Long.MIN_VALUE;
    private static boolean copyPending;
    private static int lastLoggedMeshCount = -1;
    private static int lastLoggedDynamicMeshCount = -1;
    private static int lastLoggedQuadCount = -1;
    private static int lastLoggedAlphaMaskCount = -1;

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
        int width = buffer.getInt(4 * Integer.BYTES);
        int height = buffer.getInt(5 * Integer.BYTES);
        int pixelCount = Math.multiplyExact(width, height);
        int modelBaseWord = Math.addExact(pixelBaseWord, pixelCount);
        int descriptorBaseWord = modelBaseWord;
        int quadBaseWord = Math.addExact(
                descriptorBaseWord,
                P14ModelMeshGpuLayout.MESH_DESCRIPTOR_WORDS
        );

        int usedQuadWords = Math.multiplyExact(
                snapshot.totalQuads(),
                P14ModelMeshGpuLayout.QUAD_WORDS_PER_RECORD
        );
        int alphaMaskBaseWord = Math.addExact(quadBaseWord, usedQuadWords);
        int usedMaskWords = Math.multiplyExact(
                snapshot.alphaMasks().size(),
                P14ModelMeshGpuLayout.ALPHA_MASK_WORDS_PER_RECORD
        );
        int usedWords = Math.addExact(
                P14ModelMeshGpuLayout.MESH_DESCRIPTOR_WORDS,
                Math.addExact(usedQuadWords, usedMaskWords)
        );
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

        // Mesh id zero is never traversed, so its descriptor stores scene-tail metadata instead:
        // word 0 = absolute alpha-mask pool base; word 1 = number of live non-opaque masks.
        putWord(buffer, descriptorBaseWord, alphaMaskBaseWord);
        putWord(buffer, descriptorBaseWord + 1, snapshot.alphaMasks().size());

        for (BlockModelMeshRegistry.Mesh mesh : snapshot.meshes()) {
            int descriptor = descriptorBaseWord
                    + mesh.id() * P14ModelMeshGpuLayout.MESH_DESCRIPTOR_WORDS_PER_RECORD;
            putWord(buffer, descriptor, mesh.firstQuad());
            putWord(buffer, descriptor + 1, mesh.quadCount());

            for (int quad = 0; quad < mesh.quadCount(); quad++) {
                int quadWord = quadBaseWord
                        + (mesh.firstQuad() + quad) * P14ModelMeshGpuLayout.QUAD_WORDS_PER_RECORD;

                int positionBase = quad * BlockModelMeshRegistry.FLOATS_PER_QUAD;
                for (int i = 0; i < BlockModelMeshRegistry.FLOATS_PER_QUAD; i++) {
                    putWord(
                            buffer,
                            quadWord++,
                            Float.floatToRawIntBits(mesh.positions()[positionBase + i])
                    );
                }

                int uvBase = quad * BlockModelMeshRegistry.UV_FLOATS_PER_QUAD;
                for (int i = 0; i < BlockModelMeshRegistry.UV_FLOATS_PER_QUAD; i++) {
                    putWord(
                            buffer,
                            quadWord++,
                            Float.floatToRawIntBits(mesh.uvs()[uvBase + i])
                    );
                }

                putWord(buffer, quadWord, mesh.alphaMaskIds()[quad]);
            }
        }

        for (BlockModelMeshRegistry.AlphaMask alphaMask : snapshot.alphaMasks()) {
            int maskWord = alphaMaskBaseWord
                    + (alphaMask.id() - 1) * P14ModelMeshGpuLayout.ALPHA_MASK_WORDS_PER_RECORD;
            for (int word : alphaMask.words()) {
                putWord(buffer, maskWord++, word);
            }
        }

        lastBaseByteOffset = (long) modelBaseWord * Integer.BYTES;
        lastCopyBytes = (long) usedWords * Integer.BYTES;
        lastPackedRevision = snapshot.revision();
        copyPending = true;

        int dynamicMeshes = BlockModelMeshRegistry.dynamicMeshCount();
        int alphaMasks = snapshot.alphaMasks().size();
        if (lastLoggedMeshCount != snapshot.meshes().size()
                || lastLoggedDynamicMeshCount != dynamicMeshes
                || lastLoggedQuadCount != snapshot.totalQuads()
                || lastLoggedAlphaMaskCount != alphaMasks) {
            lastLoggedMeshCount = snapshot.meshes().size();
            lastLoggedDynamicMeshCount = dynamicMeshes;
            lastLoggedQuadCount = snapshot.totalQuads();
            lastLoggedAlphaMaskCount = alphaMasks;
            TotemLumenClient.LOGGER.info(
                    "P14 model GPU registry: meshes={}, dynamicMeshes={}, quads={}, alphaMasks={}, bytes={}, maxBytes={}",
                    snapshot.meshes().size(),
                    dynamicMeshes,
                    snapshot.totalQuads(),
                    alphaMasks,
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
