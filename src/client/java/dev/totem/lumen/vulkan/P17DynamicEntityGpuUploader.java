package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.gpu.GpuDynamicEntityScene;
import dev.totem.lumen.integration.EntityRenderGeometryCache;

import java.nio.ByteBuffer;

/** Packs/copies the P17 dynamic-entity scene tail independently from static voxel payloads. */
public final class P17DynamicEntityGpuUploader {
    private static volatile long lastBaseByteOffset = -1L;
    private static volatile long lastCopyBytes;
    private static long lastPackedRevision = Long.MIN_VALUE;
    private static boolean copyPending;
    private static int lastLoggedEntityCount = -1;
    private static int lastLoggedQuadCount = -1;
    private static int lastLoggedBucketCount = -1;
    private static int lastLoggedOverflow = -1;

    private P17DynamicEntityGpuUploader() {
    }

    /** Force-packs the current entity cache during a full scene rebuild. */
    public static synchronized void pack(ByteBuffer buffer) {
        packState(buffer, EntityRenderGeometryCache.sceneState());
    }

    /** Packs only when entity movement/pose/lifecycle changed since the previous upload. */
    public static synchronized boolean packIfDirty(ByteBuffer buffer) {
        EntityRenderGeometryCache.SceneState state = EntityRenderGeometryCache.sceneState();
        if (state.revision() == lastPackedRevision) return false;
        packState(buffer, state);
        return true;
    }

    private static void packState(ByteBuffer buffer, EntityRenderGeometryCache.SceneState state) {
        int pixelBaseWord = buffer.getInt(3 * Integer.BYTES);
        int width = buffer.getInt(4 * Integer.BYTES);
        int height = buffer.getInt(5 * Integer.BYTES);
        int pixelCount = Math.multiplyExact(width, height);
        int p14BaseWord = Math.addExact(pixelBaseWord, pixelCount);
        int entityBaseWord = Math.addExact(p14BaseWord, P14ModelMeshGpuLayout.MAX_STORAGE_WORDS);

        GpuDynamicEntityScene.PackResult packed = GpuDynamicEntityScene.pack(
                buffer,
                entityBaseWord,
                state.entities()
        );
        lastBaseByteOffset = (long) entityBaseWord * Integer.BYTES;
        lastCopyBytes = packed.usedBytes();
        lastPackedRevision = state.revision();
        copyPending = true;

        if (lastLoggedEntityCount != packed.entityCount()
                || lastLoggedQuadCount != packed.totalQuads()
                || lastLoggedBucketCount != packed.sectionBucketCount()
                || lastLoggedOverflow != packed.overflowAssignments()) {
            lastLoggedEntityCount = packed.entityCount();
            lastLoggedQuadCount = packed.totalQuads();
            lastLoggedBucketCount = packed.sectionBucketCount();
            lastLoggedOverflow = packed.overflowAssignments();
            TotemLumenClient.LOGGER.info(
                    "P17 entity GPU scene: entities={}, quads={}, sectionBuckets={}, overflow={}, maxProbe={}, bytes={}, maxBytes={}",
                    packed.entityCount(),
                    packed.totalQuads(),
                    packed.sectionBucketCount(),
                    packed.overflowAssignments(),
                    packed.maxProbe(),
                    lastCopyBytes,
                    GpuDynamicEntityScene.MAX_STORAGE_BYTES
            );
        }

        if (packed.overflowAssignments() > 0) {
            TotemLumenClient.LOGGER.warn(
                    "P17 entity section candidate overflow: {} assignment(s) exceeded the {}-entity per-section baseline",
                    packed.overflowAssignments(),
                    GpuDynamicEntityScene.MAX_ENTITIES_PER_SECTION
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
}
