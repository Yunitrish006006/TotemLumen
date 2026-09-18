package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.gpu.GpuDynamicEntityScene;
import dev.totem.lumen.gpu.GpuFluidScene;
import dev.totem.lumen.gpu.GpuPbrTextureScene;
import dev.totem.lumen.integration.LabPbrTextureRegistry;
import dev.totem.lumen.material.PbrTextureData;

import java.nio.ByteBuffer;
import java.util.List;

/** Packs/copies the P18 resource-pack texture tail independently from geometry scene data. */
public final class P18PbrTextureGpuUploader {
    private static volatile long lastBaseByteOffset = -1L;
    private static volatile long lastCopyBytes;
    private static long lastPackedRevision = Long.MIN_VALUE;
    private static boolean copyPending;
    private static int lastLoggedTextureCount = -1;
    private static int lastLoggedTexelCount = -1;
    private static int lastLoggedDroppedTextures = -1;
    private static int lastLoggedAlphaTextures = -1;

    private P18PbrTextureGpuUploader() {
    }

    /** Force-pack current decoded texture state during a full scene rebuild. */
    public static synchronized void pack(ByteBuffer buffer) {
        packSnapshot(buffer, LabPbrTextureRegistry.revision(), LabPbrTextureRegistry.snapshot());
    }

    /** Pack only when resource-pack decode/reload changed the P18 texture payload. */
    public static synchronized boolean packIfDirty(ByteBuffer buffer) {
        long revision = LabPbrTextureRegistry.revision();
        if (revision == lastPackedRevision) return false;
        packSnapshot(buffer, revision, LabPbrTextureRegistry.snapshot());
        return true;
    }

    private static void packSnapshot(
            ByteBuffer buffer,
            long revision,
            List<PbrTextureData> textures
    ) {
        int pixelBaseWord = buffer.getInt(3 * Integer.BYTES);
        int capacityWidth = buffer.getInt(51 * Integer.BYTES);
        int capacityHeight = buffer.getInt(52 * Integer.BYTES);
        int pixelCount = Math.multiplyExact(capacityWidth, capacityHeight);

        int p14BaseWord = Math.addExact(pixelBaseWord, pixelCount);
        int p17BaseWord = Math.addExact(
                p14BaseWord,
                P14ModelMeshGpuLayout.MAX_STORAGE_WORDS
        );
        int p14eBaseWord = Math.addExact(
                p17BaseWord,
                GpuDynamicEntityScene.MAX_STORAGE_WORDS
        );
        int p18BaseWord = Math.addExact(
                p14eBaseWord,
                GpuFluidScene.MAX_STORAGE_WORDS
        );

        GpuPbrTextureScene.PackResult packed =
                GpuPbrTextureScene.pack(buffer, p18BaseWord, textures);

        lastBaseByteOffset = (long) p18BaseWord * Integer.BYTES;
        lastCopyBytes = packed.usedBytes();
        lastPackedRevision = revision;
        copyPending = true;

        if (lastLoggedTextureCount != packed.textureCount()
                || lastLoggedTexelCount != packed.texelCount()
                || lastLoggedDroppedTextures != packed.droppedTextures()
                || lastLoggedAlphaTextures != packed.alphaTextures()) {
            lastLoggedTextureCount = packed.textureCount();
            lastLoggedTexelCount = packed.texelCount();
            lastLoggedDroppedTextures = packed.droppedTextures();
            lastLoggedAlphaTextures = packed.alphaTextures();

            TotemLumenClient.LOGGER.info(
                    "P18 PBR GPU texture scene: textures={}, texels={}, alphaTextures={}, "
                            + "downsampled={}, dropped={}, bytes={}, maxBytes={}, format={}",
                    packed.textureCount(),
                    packed.texelCount(),
                    packed.alphaTextures(),
                    packed.downsampledTextures(),
                    packed.droppedTextures(),
                    lastCopyBytes,
                    GpuPbrTextureScene.MAX_STORAGE_BYTES,
                    LabPbrTextureRegistry.declaredFormat()
            );
            if (packed.droppedTextures() > 0) {
                TotemLumenClient.LOGGER.warn(
                        "P18 resident texture scene exceeded bounded texel capacity; "
                                + "{} texture(s) use baseline material fallback",
                        packed.droppedTextures()
                );
            }
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
}
