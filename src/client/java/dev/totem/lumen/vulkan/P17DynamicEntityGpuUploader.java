package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.gpu.GpuDynamicEntityScene;
import dev.totem.lumen.integration.EntityMaterialRuleRegistry;
import dev.totem.lumen.integration.EntityRenderGeometryCache;
import net.minecraft.client.Minecraft;

import java.nio.ByteBuffer;

/** Packs/copies the P17 dynamic-entity scene tail independently from static voxel payloads. */
public final class P17DynamicEntityGpuUploader {
    // P5 header words: 24=history read base, 26=previous-frame-valid flag. Entity movement/pose
    // currently invalidates temporal reuse conservatively until P17 hit metadata is part of the
    // per-pixel history validation contract.
    private static final int HISTORY_READ_BASE_WORD = 24;
    private static final int PREVIOUS_FRAME_VALID_WORD = 26;

    private static volatile long lastMetadataByteOffset = -1L;
    private static volatile long lastMetadataCopyBytes;
    private static volatile long lastTextureByteOffset = -1L;
    private static volatile long lastTextureCopyBytes;
    private static volatile long lastQuadByteOffset = -1L;
    private static volatile long lastQuadCopyBytes;
    private static volatile long lastPackNanos;
    private static long lastPackedRevision = Long.MIN_VALUE;
    private static long lastPackedMaterialRevision = Long.MIN_VALUE;
    private static boolean copyPending;
    private static int lastLoggedEntityCount = -1;
    private static int lastLoggedQuadCount = -1;
    private static int lastLoggedBucketCount = -1;
    private static int lastLoggedOverflow = -1;

    private P17DynamicEntityGpuUploader() {
    }

    /** Force-packs the current entity cache during a full scene rebuild. */
    public static synchronized void pack(ByteBuffer buffer) {
        ensureEntityMaterials();
        packState(buffer, EntityRenderGeometryCache.sceneState(), true);
    }

    /** Packs only when entity movement/pose/lifecycle changed since the previous upload. */
    public static synchronized boolean packIfDirty(ByteBuffer buffer) {
        ensureEntityMaterials();
        EntityRenderGeometryCache.SceneState state = EntityRenderGeometryCache.sceneState();
        long materialRevision = EntityMaterialRuleRegistry.revision();
        if (state.revision() == lastPackedRevision
                && materialRevision == lastPackedMaterialRevision) return false;

        boolean materialPayloadDirty = materialRevision != lastPackedMaterialRevision;
        packState(buffer, state, materialPayloadDirty);
        invalidateHistoryRead(buffer);
        return true;
    }

    private static void packState(
            ByteBuffer buffer,
            EntityRenderGeometryCache.SceneState state,
            boolean writeMaterialPayload
    ) {
        int pixelBaseWord = buffer.getInt(3 * Integer.BYTES);
        int capacityWidth = buffer.getInt(51 * Integer.BYTES);
        int capacityHeight = buffer.getInt(52 * Integer.BYTES);
        int pixelCount = Math.multiplyExact(capacityWidth, capacityHeight);
        int p14BaseWord = Math.addExact(pixelBaseWord, pixelCount);
        int entityBaseWord = Math.addExact(p14BaseWord, P14ModelMeshGpuLayout.MAX_STORAGE_WORDS);

        long packStartedNanos = System.nanoTime();
        GpuDynamicEntityScene.PackResult packed = writeMaterialPayload
                ? GpuDynamicEntityScene.pack(
                        buffer,
                        entityBaseWord,
                        state.entities(),
                        EntityMaterialRuleRegistry.snapshot()
                )
                : GpuDynamicEntityScene.packGeometryOnly(
                        buffer,
                        entityBaseWord,
                        state.entities(),
                        EntityMaterialRuleRegistry.snapshot()
                );

        long baseByteOffset = (long) entityBaseWord * Integer.BYTES;
        lastMetadataByteOffset = baseByteOffset;
        lastMetadataCopyBytes = (long) (
                writeMaterialPayload
                        ? GpuDynamicEntityScene.ENTITY_TEXTURE_POOL_BASE_WORD
                        : GpuDynamicEntityScene.ENTITY_MATERIAL_BASE_WORD
        ) * Integer.BYTES;

        lastTextureByteOffset = baseByteOffset
                + (long) GpuDynamicEntityScene.ENTITY_TEXTURE_POOL_BASE_WORD * Integer.BYTES;
        lastTextureCopyBytes = writeMaterialPayload
                ? (long) packed.textureTexelCount() * Integer.BYTES
                : 0L;

        lastQuadByteOffset = baseByteOffset
                + (long) GpuDynamicEntityScene.QUAD_POOL_BASE_WORD * Integer.BYTES;
        lastQuadCopyBytes = (long) packed.totalQuads()
                * GpuDynamicEntityScene.QUAD_WORDS_PER_RECORD
                * Integer.BYTES;
        lastPackNanos = Math.max(0L, System.nanoTime() - packStartedNanos);

        lastPackedRevision = state.revision();
        lastPackedMaterialRevision = EntityMaterialRuleRegistry.revision();
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
                    "P17 entity GPU scene: entities={}, quads={}, materials={}, emissiveTexels={}, "
                            + "sectionBuckets={}, overflow={}, maxProbe={}, uploadBytes={}, materialPayload={}, maxBytes={}",
                    packed.entityCount(),
                    packed.totalQuads(),
                    packed.materialCount(),
                    packed.textureTexelCount(),
                    packed.sectionBucketCount(),
                    packed.overflowAssignments(),
                    packed.maxProbe(),
                    lastMetadataCopyBytes + lastTextureCopyBytes + lastQuadCopyBytes,
                    writeMaterialPayload,
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

    private static void ensureEntityMaterials() {
        var minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            EntityMaterialRuleRegistry.ensureLoaded(minecraft.getResourceManager());
        }
    }

    private static void invalidateHistoryRead(ByteBuffer buffer) {
        buffer.putInt(HISTORY_READ_BASE_WORD * Integer.BYTES, 0);
        buffer.putInt(PREVIOUS_FRAME_VALID_WORD * Integer.BYTES, 0);
    }

    public static long lastMetadataByteOffset() {
        return lastMetadataByteOffset;
    }

    public static long lastMetadataCopyBytes() {
        return lastMetadataCopyBytes;
    }

    public static long lastTextureByteOffset() {
        return lastTextureByteOffset;
    }

    public static long lastTextureCopyBytes() {
        return lastTextureCopyBytes;
    }

    public static long lastQuadByteOffset() {
        return lastQuadByteOffset;
    }

    public static long lastQuadCopyBytes() {
        return lastQuadCopyBytes;
    }

    public static long lastPackNanos() {
        return lastPackNanos;
    }

    public static long lastUploadBytes() {
        return lastMetadataCopyBytes + lastTextureCopyBytes + lastQuadCopyBytes;
    }

    public static synchronized boolean consumeCopyPending() {
        boolean pending = copyPending;
        copyPending = false;
        return pending;
    }
}
