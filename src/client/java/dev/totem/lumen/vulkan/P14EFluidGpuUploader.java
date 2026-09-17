package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.gpu.GpuDynamicEntityScene;
import dev.totem.lumen.gpu.GpuFluidScene;
import dev.totem.lumen.integration.FluidRenderGeometryCache;

import java.nio.ByteBuffer;

/** Packs/copies the P14E exact-fluid scene tail independently from static voxel payloads. */
public final class P14EFluidGpuUploader {
    private static final int HISTORY_READ_BASE_WORD = 24;
    private static final int PREVIOUS_FRAME_VALID_WORD = 26;

    private static volatile long lastBaseByteOffset = -1L;
    private static volatile long lastCopyBytes;
    private static long lastPackedRevision = Long.MIN_VALUE;
    private static boolean copyPending;
    private static int lastLoggedCellCount = -1;
    private static int lastLoggedQuadCount = -1;
    private static int lastLoggedMaxProbe = -1;

    private P14EFluidGpuUploader() {
    }

    public static synchronized void pack(ByteBuffer buffer) {
        packState(buffer, FluidRenderGeometryCache.sceneState());
    }

    public static synchronized boolean packIfDirty(ByteBuffer buffer) {
        FluidRenderGeometryCache.SceneState state = FluidRenderGeometryCache.sceneState();
        if (state.revision() == lastPackedRevision) return false;
        packState(buffer, state);
        invalidateHistoryRead(buffer);
        return true;
    }

    private static void packState(ByteBuffer buffer, FluidRenderGeometryCache.SceneState state) {
        int pixelBaseWord = buffer.getInt(3 * Integer.BYTES);
        int width = buffer.getInt(4 * Integer.BYTES);
        int height = buffer.getInt(5 * Integer.BYTES);
        int pixelCount = Math.multiplyExact(width, height);
        int p14BaseWord = Math.addExact(pixelBaseWord, pixelCount);
        int p17BaseWord = Math.addExact(p14BaseWord, P14ModelMeshGpuLayout.MAX_STORAGE_WORDS);
        int fluidBaseWord = Math.addExact(p17BaseWord, GpuDynamicEntityScene.MAX_STORAGE_WORDS);

        GpuFluidScene.PackResult packed = GpuFluidScene.pack(buffer, fluidBaseWord, state.fluids());
        lastBaseByteOffset = (long) fluidBaseWord * Integer.BYTES;
        lastCopyBytes = packed.usedBytes();
        lastPackedRevision = state.revision();
        copyPending = true;

        if (lastLoggedCellCount != packed.cellCount()
                || lastLoggedQuadCount != packed.totalQuads()
                || lastLoggedMaxProbe != packed.maxProbe()) {
            lastLoggedCellCount = packed.cellCount();
            lastLoggedQuadCount = packed.totalQuads();
            lastLoggedMaxProbe = packed.maxProbe();
            TotemLumenClient.LOGGER.info(
                    "P14E fluid GPU scene: cells={}, quads={}, maxProbe={}, bytes={}, maxBytes={}",
                    packed.cellCount(),
                    packed.totalQuads(),
                    packed.maxProbe(),
                    lastCopyBytes,
                    GpuFluidScene.MAX_STORAGE_BYTES
            );
        }
    }

    private static void invalidateHistoryRead(ByteBuffer buffer) {
        buffer.putInt(HISTORY_READ_BASE_WORD * Integer.BYTES, 0);
        buffer.putInt(PREVIOUS_FRAME_VALID_WORD * Integer.BYTES, 0);
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
