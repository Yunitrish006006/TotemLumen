package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.gpu.GpuDynamicEntityScene;
import dev.totem.lumen.gpu.GpuFluidScene;

/** P14E-C shader transform: exact fluid triangles participate inside the shared voxel DDA. */
final class P14EFluidShaderPatch {
    static final int WATER_MATERIAL_ID = 0xFFFC;
    static final int LAVA_MATERIAL_ID = 0xFFFB;
    static final int OTHER_FLUID_MATERIAL_ID = 0xFFFA;

    private P14EFluidShaderPatch() {
    }

    static String apply(String source) {
        String traceMarker = "HitResult traceRayLimited(vec3 origin, vec3 direction, float maxDistance) {";
        int traceStart = source.indexOf(traceMarker);
        if (traceStart < 0) {
            throw new IllegalStateException("P14E fluid shader patch marker missing: traceRayLimited");
        }

        String helpers = ("""
                const uint P14E_FLUID_ABI_VERSION = %du;
                const uint P14E_MAX_FLUID_CELLS = %du;
                const uint P14E_LOOKUP_CAPACITY = %du;
                const uint P14E_LOOKUP_MASK = %du;
                const uint P14E_CELL_DESCRIPTOR_WORDS = %du;
                const uint P14E_LOOKUP_WORDS_PER_BUCKET = %du;
                const uint P14E_CELL_DESCRIPTOR_BASE = %du;
                const uint P14E_LOOKUP_BASE = %du;
                const uint P14E_QUAD_POOL_BASE = %du;
                const uint P14E_QUAD_WORDS = %du;
                const uint P14E_P14_MAX_STORAGE_WORDS = %du;
                const uint P14E_P17_MAX_STORAGE_WORDS = %du;
                const uint P14E_CELL_FLAG_FLUID_ONLY = %du;
                const uint P14E_WATER_MATERIAL_ID = 0xFFFCu;
                const uint P14E_LAVA_MATERIAL_ID = 0xFFFBu;
                const uint P14E_OTHER_FLUID_MATERIAL_ID = 0xFFFAu;
                const uint P14E_INVALID_INDEX = 0xFFFFFFFFu;

                uint p14eFluidSceneBase() {
                    uint pixelCount = scene.data[4] * scene.data[5];
                    return scene.data[3] + pixelCount
                            + P14E_P14_MAX_STORAGE_WORDS
                            + P14E_P17_MAX_STORAGE_WORDS;
                }

                uint p14eFindFluidCell(uint fluidBase, ivec3 voxel) {
                    if (scene.data[fluidBase] != P14E_FLUID_ABI_VERSION) return P14E_INVALID_INDEX;
                    uint start = sectionHash(voxel) & P14E_LOOKUP_MASK;
                    for (uint probe = 0u; probe < P14E_LOOKUP_CAPACITY; probe++) {
                        uint bucket = (start + probe) & P14E_LOOKUP_MASK;
                        uint base = fluidBase + P14E_LOOKUP_BASE
                                + bucket * P14E_LOOKUP_WORDS_PER_BUCKET;
                        uint indexPlusOne = scene.data[base + 3u];
                        if (indexPlusOne == 0u) return P14E_INVALID_INDEX;
                        ivec3 candidate = ivec3(
                            int(scene.data[base]),
                            int(scene.data[base + 1u]),
                            int(scene.data[base + 2u])
                        );
                        if (all(equal(candidate, voxel))) {
                            uint index = indexPlusOne - 1u;
                            return index < min(scene.data[fluidBase + 1u], P14E_MAX_FLUID_CELLS)
                                    ? index
                                    : P14E_INVALID_INDEX;
                        }
                    }
                    return P14E_INVALID_INDEX;
                }

                vec3 p14eFluidVertex(uint fluidBase, uint quadWord) {
                    uint base = fluidBase + P14E_QUAD_POOL_BASE + quadWord;
                    return vec3(
                        uintBitsToFloat(scene.data[base]),
                        uintBitsToFloat(scene.data[base + 1u]),
                        uintBitsToFloat(scene.data[base + 2u])
                    );
                }

                void p14eTryTriangle(
                        vec3 origin,
                        vec3 direction,
                        vec3 a,
                        vec3 b,
                        vec3 c,
                        float minDistance,
                        float maxDistance,
                        inout bool found,
                        inout float bestDistance,
                        inout ivec3 bestNormal
                ) {
                    vec3 edge1 = b - a;
                    vec3 edge2 = c - a;
                    vec3 p = cross(direction, edge2);
                    float determinant = dot(edge1, p);
                    if (abs(determinant) < 0.0000001) return;

                    float inverseDeterminant = 1.0 / determinant;
                    vec3 tvec = origin - a;
                    float u = dot(tvec, p) * inverseDeterminant;
                    if (u < -0.00001 || u > 1.00001) return;
                    vec3 q = cross(tvec, edge1);
                    float v = dot(direction, q) * inverseDeterminant;
                    if (v < -0.00001 || u + v > 1.00001) return;

                    float distance = dot(edge2, q) * inverseDeterminant;
                    if (distance < minDistance - 0.00001 || distance > maxDistance + 0.00001) return;
                    if (found && distance >= bestDistance) return;

                    vec3 geometricNormal = cross(edge1, edge2);
                    float normalLength = length(geometricNormal);
                    if (normalLength < 0.000001) return;
                    geometricNormal /= normalLength;
                    if (dot(geometricNormal, direction) > 0.0) geometricNormal = -geometricNormal;

                    found = true;
                    bestDistance = max(distance, 0.0);
                    bestNormal = ivec3(round(clamp(
                        geometricNormal,
                        vec3(-1.0),
                        vec3(1.0)
                    ) * 32767.0));
                }

                bool p14eIntersectFluidCell(
                        uint fluidBase,
                        uint fluidIndex,
                        vec3 origin,
                        vec3 direction,
                        float cellEntryDistance,
                        float cellExitDistance,
                        out float hitDistance,
                        out ivec3 hitNormal,
                        out uint hitMaterialId,
                        out bool fluidOnly
                ) {
                    uint descriptor = fluidBase + P14E_CELL_DESCRIPTOR_BASE
                            + fluidIndex * P14E_CELL_DESCRIPTOR_WORDS;
                    ivec3 blockCoord = ivec3(
                        int(scene.data[descriptor]),
                        int(scene.data[descriptor + 1u]),
                        int(scene.data[descriptor + 2u])
                    );
                    uint fluidKind = scene.data[descriptor + 3u];
                    uint firstQuad = scene.data[descriptor + 5u];
                    uint quadCount = min(scene.data[descriptor + 6u], 16u);
                    uint flags = scene.data[descriptor + 7u];
                    fluidOnly = (flags & P14E_CELL_FLAG_FLUID_ONLY) != 0u;
                    hitMaterialId = fluidKind == 1u
                            ? P14E_WATER_MATERIAL_ID
                            : (fluidKind == 2u ? P14E_LAVA_MATERIAL_ID : P14E_OTHER_FLUID_MATERIAL_ID);

                    ivec3 sectionCoord = ivec3(
                        floorDiv16(blockCoord.x),
                        floorDiv16(blockCoord.y),
                        floorDiv16(blockCoord.z)
                    );
                    vec3 sectionOrigin = vec3(sectionCoord * 16);
                    bool found = false;
                    float bestDistance = cellExitDistance;
                    ivec3 bestNormal = ivec3(0);

                    for (uint quadIndex = 0u; quadIndex < quadCount; quadIndex++) {
                        uint quadWord = (firstQuad + quadIndex) * P14E_QUAD_WORDS;
                        vec3 v0 = sectionOrigin + p14eFluidVertex(fluidBase, quadWord);
                        vec3 v1 = sectionOrigin + p14eFluidVertex(fluidBase, quadWord + 3u);
                        vec3 v2 = sectionOrigin + p14eFluidVertex(fluidBase, quadWord + 6u);
                        vec3 v3 = sectionOrigin + p14eFluidVertex(fluidBase, quadWord + 9u);
                        p14eTryTriangle(
                            origin, direction, v0, v1, v2,
                            cellEntryDistance, min(cellExitDistance, bestDistance),
                            found, bestDistance, bestNormal
                        );
                        p14eTryTriangle(
                            origin, direction, v0, v2, v3,
                            cellEntryDistance, min(cellExitDistance, bestDistance),
                            found, bestDistance, bestNormal
                        );
                    }

                    hitDistance = bestDistance;
                    hitNormal = bestNormal;
                    return found;
                }

                """).formatted(
                GpuFluidScene.ABI_VERSION,
                GpuFluidScene.MAX_FLUID_CELLS,
                GpuFluidScene.LOOKUP_CAPACITY,
                GpuFluidScene.LOOKUP_CAPACITY - 1,
                GpuFluidScene.CELL_DESCRIPTOR_WORDS_PER_RECORD,
                GpuFluidScene.LOOKUP_WORDS_PER_BUCKET,
                GpuFluidScene.CELL_DESCRIPTOR_BASE_WORD,
                GpuFluidScene.LOOKUP_BASE_WORD,
                GpuFluidScene.QUAD_POOL_BASE_WORD,
                GpuFluidScene.QUAD_WORDS_PER_RECORD,
                P14ModelMeshGpuLayout.MAX_STORAGE_WORDS,
                GpuDynamicEntityScene.MAX_STORAGE_WORDS,
                GpuFluidScene.CELL_FLAG_FLUID_ONLY
        );
        source = source.substring(0, traceStart) + helpers + source.substring(traceStart);

        traceStart = source.indexOf(traceMarker, traceStart + helpers.length());
        String iterationMarker = "for (uint iteration = 0u; iteration < maxSteps; iteration++) {";
        int iterationStart = source.indexOf(iterationMarker, traceStart);
        String candidateStartMarker = "uint voxelWord = voxelWordAt(voxel);";
        int candidateStart = source.indexOf(candidateStartMarker, iterationStart);
        String traversalMarker = "if (tMax.x <= tMax.y && tMax.x <= tMax.z) {";
        int traversalStart = source.indexOf(traversalMarker, candidateStart);
        if (iterationStart < 0 || candidateStart < 0 || traversalStart < 0) {
            throw new IllegalStateException("P14E fluid shader patch marker missing: DDA candidate span");
        }

        String candidateReplacement = """
                        uint voxelWord = voxelWordAt(voxel);
                        uint materialId = voxelWord & 0xFFFFu;
                        uint geometryCode = voxelWord >> 16u;
                        float cellExitDistance = min(tMax.x, min(tMax.y, tMax.z));

                        uint p14eFluidBase = p14eFluidSceneBase();
                        uint p14eFluidIndex = p14eFindFluidCell(p14eFluidBase, voxel);
                        bool p14eHasFluid = p14eFluidIndex != P14E_INVALID_INDEX;
                        bool p14eFluidOnly = false;
                        bool p14eFluidHit = false;
                        float p14eFluidDistance = cellExitDistance;
                        ivec3 p14eFluidNormal = ivec3(0);
                        uint p14eFluidMaterialId = P14E_OTHER_FLUID_MATERIAL_ID;
                        if (p14eHasFluid) {
                            p14eFluidHit = p14eIntersectFluidCell(
                                p14eFluidBase,
                                p14eFluidIndex,
                                origin,
                                dir,
                                distance,
                                min(cellExitDistance, maxDistance),
                                p14eFluidDistance,
                                p14eFluidNormal,
                                p14eFluidMaterialId,
                                p14eFluidOnly
                            );
                        }

                        bool p14eStaticHit = false;
                        float p14eStaticDistance = distance;
                        ivec3 p14eStaticNormal = normal;
                        if (materialId != 0u && !p14eFluidOnly) {
                            if (p14UsesLocalGeometry(geometryCode)) {
                                p14eStaticHit = p14IntersectVoxelGeometry(
                                    origin,
                                    dir,
                                    voxel,
                                    geometryCode,
                                    distance,
                                    min(cellExitDistance, maxDistance),
                                    p14eStaticDistance,
                                    p14eStaticNormal
                                );
                            } else {
                                p14eStaticHit = true;
                            }
                        }

                        if (p14eFluidHit
                                && (!p14eStaticHit || p14eFluidDistance < p14eStaticDistance - 0.00001)) {
                            result.hit = 1u;
                            result.materialId = p14eFluidMaterialId;
                            result.voxel = voxel;
                            result.normal = p14eFluidNormal;
                            result.distance = p14eFluidDistance;
                            result.steps = iteration;
                            return result;
                        }
                        if (p14eStaticHit) {
                            result.hit = 1u;
                            result.materialId = materialId;
                            result.voxel = voxel;
                            result.normal = p14eStaticNormal;
                            result.distance = p14eStaticDistance;
                            result.steps = iteration;
                            return result;
                        }

                        """;
        source = source.substring(0, candidateStart)
                + candidateReplacement
                + source.substring(traversalStart);
        source = patchFluidDebugView(source);

        TotemLumenClient.LOGGER.info(
                "P14E exact fluid tracing active: abi={}, blockLookup={}, maxCells={}, maxQuads={}, waterloggedCoexistence=true, sharedDda=true",
                GpuFluidScene.ABI_VERSION,
                GpuFluidScene.LOOKUP_CAPACITY,
                GpuFluidScene.MAX_FLUID_CELLS,
                GpuFluidScene.MAX_FLUID_QUADS
        );
        return source;
    }

    private static String patchFluidDebugView(String source) {
        String debugMarker = "uint debugColor(HitResult hit, vec3 primaryOrigin, vec3 primaryDirection) {";
        if (!source.contains(debugMarker)) return source;

        String helper = """
                vec3 p14eFluidDebugColor(HitResult hit) {
                    uint fluidBase = p14eFluidSceneBase();
                    uint fluidIndex = p14eFindFluidCell(fluidBase, hit.voxel);
                    if (fluidIndex == P14E_INVALID_INDEX) {
                        return vec3(0.035, 0.035, 0.045);
                    }

                    uint descriptor = fluidBase + P14E_CELL_DESCRIPTOR_BASE
                            + fluidIndex * P14E_CELL_DESCRIPTOR_WORDS;
                    uint flags = scene.data[descriptor + 7u];
                    bool fluidOnly = (flags & P14E_CELL_FLAG_FLUID_ONLY) != 0u;
                    bool exactFluidHit = hit.materialId == P14E_WATER_MATERIAL_ID
                            || hit.materialId == P14E_LAVA_MATERIAL_ID
                            || hit.materialId == P14E_OTHER_FLUID_MATERIAL_ID;

                    if (!exactFluidHit) {
                        // Static geometry won inside a coexistence cell (for example a
                        // waterlogged stair/fence). Bright green makes coexistence obvious.
                        return fluidOnly
                                ? vec3(0.12, 0.12, 0.14)
                                : vec3(0.18, 1.00, 0.25);
                    }

                    if (!fluidOnly) {
                        // Exact fluid surface inside a waterlogged/custom coexistence cell.
                        return vec3(0.86, 0.18, 1.00);
                    }
                    if (hit.materialId == P14E_WATER_MATERIAL_ID) {
                        return vec3(0.06, 0.72, 1.00);
                    }
                    if (hit.materialId == P14E_LAVA_MATERIAL_ID) {
                        return vec3(1.00, 0.20, 0.02);
                    }
                    return vec3(1.00, 0.82, 0.10);
                }

                """;
        source = source.replace(debugMarker, helper + debugMarker);

        String modeMarker = """
                        if (mode == 6u) {
                            return localLightColor(hit, primaryOrigin, primaryDirection, true);
                        }
                        return softShadowColor(hit, primaryOrigin, primaryDirection);
                """;
        String modeReplacement = """
                        if (mode == 6u) {
                            return localLightColor(hit, primaryOrigin, primaryDirection, true);
                        }
                        if (mode == 12u) {
                            return packRgba(p14eFluidDebugColor(hit), 255u);
                        }
                        return softShadowColor(hit, primaryOrigin, primaryDirection);
                """;
        if (!source.contains(modeMarker)) {
            throw new IllegalStateException("P14E fluid debug-view marker missing: debugColor fallback");
        }
        return source.replace(modeMarker, modeReplacement);
    }
}
