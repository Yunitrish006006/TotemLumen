package dev.totem.lumen.integration;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.geometry.BlockModelMeshRegistry;
import dev.totem.lumen.scene.BlockGeometryCode;
import dev.totem.lumen.scene.SectionCoordinates;
import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * P14D bridge between render-submitted block-entity geometry and section voxel extraction.
 *
 * <p>Renderer geometry is supplemental: the owning block's ordinary P14C quads are retained and
 * concatenated with the block-entity model. Once the section has resolved a stable dynamic mesh id,
 * later animation updates only replace that mesh payload; the voxel word does not change.</p>
 */
public final class BlockEntityRenderGeometryCache {
    private static final Map<BlockModelMeshRegistry.DynamicMeshKey, Entry> ENTRIES = new HashMap<>();
    private static boolean firstCaptureLogged;

    private BlockEntityRenderGeometryCache() {
    }

    public static void updateSupplemental(
            String dimensionId,
            BlockPos pos,
            String typeId,
            float[] supplementalPositions
    ) {
        if (dimensionId == null || pos == null || supplementalPositions == null) return;
        BlockModelMeshRegistry.DynamicMeshKey key = key(dimensionId, pos);
        boolean needsSectionRefresh = false;
        int meshId = -1;
        int quads = supplementalPositions.length / BlockModelMeshRegistry.FLOATS_PER_QUAD;

        synchronized (ENTRIES) {
            Entry entry = ENTRIES.get(key);
            if (entry == null) {
                if (supplementalPositions.length == 0) return;
                entry = new Entry(typeId, null, supplementalPositions.clone(), -1);
                ENTRIES.put(key, entry);
                needsSectionRefresh = true;
            } else {
                if (Arrays.equals(entry.supplementalPositions, supplementalPositions)) return;
                entry.supplementalPositions = supplementalPositions.clone();
                entry.typeId = typeId;
                if (supplementalPositions.length == 0) {
                    if (entry.meshId > 0) BlockModelMeshRegistry.releaseDynamic(key);
                    entry.meshId = -1;
                    entry.staticPositions = null;
                    needsSectionRefresh = true;
                } else if (entry.staticPositions != null) {
                    BlockModelMeshRegistry.DynamicUpsertResult result = BlockModelMeshRegistry.upsertDynamic(
                            key,
                            concatenate(entry.staticPositions, entry.supplementalPositions)
                    );
                    if (result.meshId() > 0) entry.meshId = result.meshId();
                    if (result.firstRegistration()) needsSectionRefresh = true;
                    meshId = entry.meshId;
                } else {
                    needsSectionRefresh = true;
                }
            }
        }

        if (!firstCaptureLogged && supplementalPositions.length > 0) {
            firstCaptureLogged = true;
            TotemLumenClient.LOGGER.info(
                    "P14D block-entity geometry capture active: type={}, quads={}, stableMeshId={}",
                    typeId,
                    quads,
                    meshId < 0 ? "pending-section-resolve" : Integer.toString(meshId)
            );
        }
        if (needsSectionRefresh) {
            SceneExtractionBridge.refreshBlockEntityGeometry(pos);
        }
    }

    /**
     * Called from P14C section extraction. Returns a stable MODEL_MESH code when supplemental
     * renderer geometry exists, otherwise null so the ordinary static resolver continues.
     */
    public static Integer resolveGeometryCode(
            String dimensionId,
            BlockPos pos,
            float[] staticPositions
    ) {
        BlockModelMeshRegistry.DynamicMeshKey key = key(dimensionId, pos);
        synchronized (ENTRIES) {
            Entry entry = ENTRIES.get(key);
            if (entry == null || entry.supplementalPositions.length == 0) return null;

            entry.staticPositions = staticPositions == null ? new float[0] : staticPositions.clone();
            BlockModelMeshRegistry.DynamicUpsertResult result = BlockModelMeshRegistry.upsertDynamic(
                    key,
                    concatenate(entry.staticPositions, entry.supplementalPositions)
            );
            if (result.meshId() <= 0) return null;
            entry.meshId = result.meshId();
            return BlockGeometryCode.modelMesh(result.meshId());
        }
    }

    public static void releaseChunk(String dimensionId, int chunkX, int chunkZ) {
        synchronized (ENTRIES) {
            ENTRIES.entrySet().removeIf(entry -> {
                BlockModelMeshRegistry.DynamicMeshKey key = entry.getKey();
                boolean matches = key.dimensionId().equals(dimensionId)
                        && SectionCoordinates.blockToSection(key.x()) == chunkX
                        && SectionCoordinates.blockToSection(key.z()) == chunkZ;
                if (matches) BlockModelMeshRegistry.releaseDynamic(key);
                return matches;
            });
        }
    }

    public static void clear() {
        synchronized (ENTRIES) {
            for (BlockModelMeshRegistry.DynamicMeshKey key : ENTRIES.keySet()) {
                BlockModelMeshRegistry.releaseDynamic(key);
            }
            ENTRIES.clear();
        }
    }

    public static int entryCount() {
        synchronized (ENTRIES) {
            return ENTRIES.size();
        }
    }

    private static BlockModelMeshRegistry.DynamicMeshKey key(String dimensionId, BlockPos pos) {
        return new BlockModelMeshRegistry.DynamicMeshKey(
                dimensionId,
                pos.getX(),
                pos.getY(),
                pos.getZ()
        );
    }

    private static float[] concatenate(float[] staticPositions, float[] supplementalPositions) {
        float[] combined = Arrays.copyOf(
                staticPositions,
                staticPositions.length + supplementalPositions.length
        );
        System.arraycopy(
                supplementalPositions,
                0,
                combined,
                staticPositions.length,
                supplementalPositions.length
        );
        return combined;
    }

    private static final class Entry {
        String typeId;
        float[] staticPositions;
        float[] supplementalPositions;
        int meshId;

        Entry(String typeId, float[] staticPositions, float[] supplementalPositions, int meshId) {
            this.typeId = typeId;
            this.staticPositions = staticPositions;
            this.supplementalPositions = supplementalPositions;
            this.meshId = meshId;
        }
    }
}
