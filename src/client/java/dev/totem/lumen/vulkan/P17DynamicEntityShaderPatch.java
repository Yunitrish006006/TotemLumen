package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.gpu.GpuDynamicEntityScene;

/**
 * P17C shader transform that layers dynamic entity triangles over the proven P14 static trace.
 *
 * <p>The original voxel/P14 trace is renamed but otherwise preserved. A second section-level DDA
 * walks only P17 section-candidate buckets, intersects entity AABBs and then entity quads, and the
 * public traceRayLimited() returns whichever static/entity hit is nearer. Because P15/P13/P16 all
 * consume that shared trace entry point, one implementation feeds primary rays, visibility, GI and
 * reflection rather than creating feature-specific entity geometry paths.</p>
 */
final class P17DynamicEntityShaderPatch {
    static final int ENTITY_MATERIAL_ID = 0xFFFE;
    static final int SPIDER_ENTITY_MATERIAL_ID = 0xFFFD;

    private P17DynamicEntityShaderPatch() {
    }

    static String apply(String source) {
        String traceMarker = "HitResult traceRayLimited(vec3 origin, vec3 direction, float maxDistance) {";
        source = replaceRequiredOnce(
                source,
                traceMarker,
                "HitResult p17TraceStaticRayLimited(vec3 origin, vec3 direction, float maxDistance) {",
                "static trace rename"
        );

        String traceEntryMarker = "HitResult traceRay(vec3 origin, vec3 direction) {";
        if (!source.contains(traceEntryMarker)) {
            throw new IllegalStateException("P17 shader patch marker missing: traceRay entry");
        }

        String helpers = ("""
                const uint P17_ENTITY_MATERIAL_ID = 0xFFFEu;
                const uint P17_SPIDER_ENTITY_MATERIAL_ID = 0xFFFDu;
                const uint P17_ENTITY_ABI_VERSION = 2u;
                const uint P17_ENTITY_FLAG_SPIDER_EYES = %du;
                const uint P17_MAX_ENTITIES = %du;
                const uint P17_SECTION_LOOKUP_CAPACITY = %du;
                const uint P17_SECTION_LOOKUP_MASK = %du;
                const uint P17_MAX_ENTITIES_PER_SECTION = %du;
                const uint P17_ENTITY_DESCRIPTOR_WORDS = %du;
                const uint P17_SECTION_BUCKET_WORDS = %du;
                const uint P17_ENTITY_DESCRIPTOR_BASE = %du;
                const uint P17_SECTION_LOOKUP_BASE = %du;
                const uint P17_SPIDER_EYE_POOL_BASE = %du;
                const uint P17_QUAD_POOL_BASE = %du;
                const uint P17_QUAD_WORDS = %du;
                const uint P17_P14_MAX_STORAGE_WORDS = %du;

                uint p17EntitySceneBase() {
                    uint pixelCount = scene.data[51] * scene.data[52];
                    return scene.data[3] + pixelCount + P17_P14_MAX_STORAGE_WORDS;
                }

                uint p17FindSectionBucket(uint entityBase, ivec3 sectionCoord) {
                    uint start = sectionHash(sectionCoord) & P17_SECTION_LOOKUP_MASK;
                    for (uint probe = 0u; probe < P17_SECTION_LOOKUP_CAPACITY; probe++) {
                        uint bucket = (start + probe) & P17_SECTION_LOOKUP_MASK;
                        uint base = entityBase + P17_SECTION_LOOKUP_BASE
                                + bucket * P17_SECTION_BUCKET_WORDS;
                        uint count = scene.data[base + 3u];
                        if (count == 0u) return 0xFFFFFFFFu;
                        ivec3 candidate = ivec3(
                            int(scene.data[base]),
                            int(scene.data[base + 1u]),
                            int(scene.data[base + 2u])
                        );
                        if (all(equal(candidate, sectionCoord))) return base;
                    }
                    return 0xFFFFFFFFu;
                }

                vec3 p17Vertex(uint entityBase, uint wordBase) {
                    uint base = entityBase + P17_QUAD_POOL_BASE + wordBase;
                    return vec3(
                        uintBitsToFloat(scene.data[base]),
                        uintBitsToFloat(scene.data[base + 1u]),
                        uintBitsToFloat(scene.data[base + 2u])
                    );
                }

                vec2 p17Uv(uint entityBase, uint wordBase) {
                    uint base = entityBase + P17_QUAD_POOL_BASE + wordBase;
                    return vec2(
                        uintBitsToFloat(scene.data[base]),
                        uintBitsToFloat(scene.data[base + 1u])
                    );
                }

                uint p17PackUv(vec2 uv) {
                    vec2 wrapped = fract(uv);
                    uint u = uint(round(clamp(wrapped.x, 0.0, 1.0) * 65535.0));
                    uint v = uint(round(clamp(wrapped.y, 0.0, 1.0) * 65535.0));
                    return u | (v << 16u);
                }

                vec2 p17UnpackUv(uint packed) {
                    return vec2(
                        float(packed & 65535u),
                        float((packed >> 16u) & 65535u)
                    ) / 65535.0;
                }

                vec3 p17ArgbRgb(uint argb) {
                    return vec3(
                        float((argb >> 16u) & 255u),
                        float((argb >> 8u) & 255u),
                        float(argb & 255u)
                    ) / 255.0;
                }

                uint p17SpiderEyeArgb(uint entityBase, vec2 uv) {
                    uint width = scene.data[entityBase + 8u];
                    uint height = scene.data[entityBase + 9u];
                    if (width == 0u || height == 0u) return 0u;
                    vec2 wrapped = fract(uv);
                    uint x = min(width - 1u, uint(floor(wrapped.x * float(width))));
                    uint y = min(height - 1u, uint(floor(wrapped.y * float(height))));
                    return scene.data[
                        entityBase + P17_SPIDER_EYE_POOL_BASE + y * width + x
                    ];
                }

                bool p17IntersectAabb(
                        vec3 origin,
                        vec3 direction,
                        vec3 boundsMin,
                        vec3 boundsMax,
                        float minDistance,
                        float maxDistance
                ) {
                    float nearDistance = -1.0e30;
                    float farDistance = 1.0e30;

                    if (abs(direction.x) < 0.000001) {
                        if (origin.x < boundsMin.x || origin.x > boundsMax.x) return false;
                    } else {
                        float t0 = (boundsMin.x - origin.x) / direction.x;
                        float t1 = (boundsMax.x - origin.x) / direction.x;
                        nearDistance = max(nearDistance, min(t0, t1));
                        farDistance = min(farDistance, max(t0, t1));
                    }
                    if (abs(direction.y) < 0.000001) {
                        if (origin.y < boundsMin.y || origin.y > boundsMax.y) return false;
                    } else {
                        float t0 = (boundsMin.y - origin.y) / direction.y;
                        float t1 = (boundsMax.y - origin.y) / direction.y;
                        nearDistance = max(nearDistance, min(t0, t1));
                        farDistance = min(farDistance, max(t0, t1));
                    }
                    if (abs(direction.z) < 0.000001) {
                        if (origin.z < boundsMin.z || origin.z > boundsMax.z) return false;
                    } else {
                        float t0 = (boundsMin.z - origin.z) / direction.z;
                        float t1 = (boundsMax.z - origin.z) / direction.z;
                        nearDistance = max(nearDistance, min(t0, t1));
                        farDistance = min(farDistance, max(t0, t1));
                    }

                    float candidate = max(nearDistance, minDistance);
                    return farDistance + 0.00001 >= candidate
                            && farDistance >= -0.00001
                            && candidate <= maxDistance + 0.00001;
                }

                void p17TryTriangle(
                        vec3 origin,
                        vec3 direction,
                        vec3 a,
                        vec3 b,
                        vec3 c,
                        vec2 uvA,
                        vec2 uvB,
                        vec2 uvC,
                        uint candidateMaterialId,
                        float minDistance,
                        float maxDistance,
                        inout bool found,
                        inout float bestDistance,
                        inout vec3 bestNormal,
                        inout vec2 bestUv,
                        inout uint bestMaterialId
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
                    bestNormal = geometricNormal;
                    bestUv = uvA * (1.0 - u - v) + uvB * u + uvC * v;
                    bestMaterialId = candidateMaterialId;
                }

                bool p17TrySectionEntities(
                        uint entityBase,
                        ivec3 sectionCoord,
                        vec3 origin,
                        vec3 direction,
                        float minDistance,
                        float maxDistance,
                        out float hitDistance,
                        out vec3 hitNormal,
                        out uint hitMaterialId,
                        out uint hitPackedUv
                ) {
                    uint bucket = p17FindSectionBucket(entityBase, sectionCoord);
                    if (bucket == 0xFFFFFFFFu) return false;

                    uint entityCount = min(scene.data[entityBase + 1u], P17_MAX_ENTITIES);
                    uint candidateCount = min(
                        scene.data[bucket + 3u],
                        P17_MAX_ENTITIES_PER_SECTION
                    );
                    bool found = false;
                    float bestDistance = maxDistance;
                    vec3 bestNormal = vec3(0.0);
                    vec2 bestUv = vec2(0.0);
                    uint bestMaterialId = P17_ENTITY_MATERIAL_ID;

                    for (uint candidateIndex = 0u; candidateIndex < candidateCount; candidateIndex++) {
                        uint entityIndex = scene.data[bucket + 4u + candidateIndex];
                        if (entityIndex >= entityCount) continue;
                        uint descriptor = entityBase + P17_ENTITY_DESCRIPTOR_BASE
                                + entityIndex * P17_ENTITY_DESCRIPTOR_WORDS;
                        ivec3 originSection = ivec3(
                            int(scene.data[descriptor + 2u]),
                            int(scene.data[descriptor + 3u]),
                            int(scene.data[descriptor + 4u])
                        );
                        vec3 sectionOrigin = vec3(originSection) * 16.0;
                        vec3 entityOrigin = sectionOrigin + vec3(
                            uintBitsToFloat(scene.data[descriptor + 5u]),
                            uintBitsToFloat(scene.data[descriptor + 6u]),
                            uintBitsToFloat(scene.data[descriptor + 7u])
                        );
                        vec3 boundsMin = sectionOrigin + vec3(
                            uintBitsToFloat(scene.data[descriptor + 8u]),
                            uintBitsToFloat(scene.data[descriptor + 9u]),
                            uintBitsToFloat(scene.data[descriptor + 10u])
                        );
                        vec3 boundsMax = sectionOrigin + vec3(
                            uintBitsToFloat(scene.data[descriptor + 11u]),
                            uintBitsToFloat(scene.data[descriptor + 12u]),
                            uintBitsToFloat(scene.data[descriptor + 13u])
                        );
                        if (!p17IntersectAabb(
                                origin,
                                direction,
                                boundsMin,
                                boundsMax,
                                minDistance,
                                min(maxDistance, bestDistance)
                        )) continue;

                        uint entityFlags = scene.data[descriptor + 16u];
                        uint candidateMaterialId =
                                (entityFlags & P17_ENTITY_FLAG_SPIDER_EYES) != 0u
                                ? P17_SPIDER_ENTITY_MATERIAL_ID
                                : P17_ENTITY_MATERIAL_ID;
                        uint firstQuad = scene.data[descriptor + 14u];
                        uint quadCount = scene.data[descriptor + 15u];
                        for (uint quadIndex = 0u; quadIndex < quadCount; quadIndex++) {
                            uint quadWord = (firstQuad + quadIndex) * P17_QUAD_WORDS;
                            vec3 v0 = entityOrigin + p17Vertex(entityBase, quadWord);
                            vec3 v1 = entityOrigin + p17Vertex(entityBase, quadWord + 3u);
                            vec3 v2 = entityOrigin + p17Vertex(entityBase, quadWord + 6u);
                            vec3 v3 = entityOrigin + p17Vertex(entityBase, quadWord + 9u);
                            vec2 uv0 = p17Uv(entityBase, quadWord + 12u);
                            vec2 uv1 = p17Uv(entityBase, quadWord + 14u);
                            vec2 uv2 = p17Uv(entityBase, quadWord + 16u);
                            vec2 uv3 = p17Uv(entityBase, quadWord + 18u);
                            p17TryTriangle(
                                origin, direction, v0, v1, v2,
                                uv0, uv1, uv2, candidateMaterialId,
                                minDistance, min(maxDistance, bestDistance),
                                found, bestDistance, bestNormal, bestUv, bestMaterialId
                            );
                            p17TryTriangle(
                                origin, direction, v0, v2, v3,
                                uv0, uv2, uv3, candidateMaterialId,
                                minDistance, min(maxDistance, bestDistance),
                                found, bestDistance, bestNormal, bestUv, bestMaterialId
                            );
                        }
                    }

                    hitDistance = bestDistance;
                    hitNormal = bestNormal;
                    hitMaterialId = bestMaterialId;
                    hitPackedUv = p17PackUv(bestUv);
                    return found;
                }

                HitResult p17TraceEntityRayLimited(vec3 origin, vec3 direction, float maxDistance) {
                    HitResult result;
                    result.hit = 0u;
                    result.materialId = 0u;
                    result.voxel = ivec3(0);
                    result.normal = ivec3(0);
                    result.distance = 0.0;
                    result.steps = 0u;

                    uint entityBase = p17EntitySceneBase();
                    if (scene.data[entityBase] != P17_ENTITY_ABI_VERSION
                            || scene.data[entityBase + 1u] == 0u) return result;

                    float directionLength = length(direction);
                    if (directionLength < 0.000001 || maxDistance <= 0.0) return result;
                    vec3 dir = direction / directionLength;
                    ivec3 sectionCoord = ivec3(floor(origin / 16.0));
                    ivec3 step = ivec3(
                        dir.x > 0.0 ? 1 : (dir.x < 0.0 ? -1 : 0),
                        dir.y > 0.0 ? 1 : (dir.y < 0.0 ? -1 : 0),
                        dir.z > 0.0 ? 1 : (dir.z < 0.0 ? -1 : 0)
                    );
                    const float INF = 1.0e30;
                    vec3 tDelta = vec3(
                        step.x == 0 ? INF : abs(16.0 / dir.x),
                        step.y == 0 ? INF : abs(16.0 / dir.y),
                        step.z == 0 ? INF : abs(16.0 / dir.z)
                    );
                    vec3 tMax = vec3(
                        step.x > 0
                            ? (((float(sectionCoord.x) + 1.0) * 16.0) - origin.x) / dir.x
                            : (step.x < 0 ? ((float(sectionCoord.x) * 16.0) - origin.x) / dir.x : INF),
                        step.y > 0
                            ? (((float(sectionCoord.y) + 1.0) * 16.0) - origin.y) / dir.y
                            : (step.y < 0 ? ((float(sectionCoord.y) * 16.0) - origin.y) / dir.y : INF),
                        step.z > 0
                            ? (((float(sectionCoord.z) + 1.0) * 16.0) - origin.z) / dir.z
                            : (step.z < 0 ? ((float(sectionCoord.z) * 16.0) - origin.z) / dir.z : INF)
                    );

                    float sectionEntry = 0.0;
                    for (uint sectionStep = 0u; sectionStep < 96u; sectionStep++) {
                        float sectionExit = min(tMax.x, min(tMax.y, tMax.z));
                        float segmentEnd = min(sectionExit, maxDistance);
                        float entityDistance;
                        vec3 entityNormal;
                        uint entityMaterialId;
                        uint entityPackedUv;
                        if (p17TrySectionEntities(
                                entityBase,
                                sectionCoord,
                                origin,
                                dir,
                                sectionEntry,
                                segmentEnd,
                                entityDistance,
                                entityNormal,
                                entityMaterialId,
                                entityPackedUv
                        )) {
                            vec3 hitPoint = origin + dir * entityDistance;
                            result.hit = 1u;
                            result.materialId = entityMaterialId;
                            result.voxel = ivec3(floor(hitPoint));
                            result.normal = ivec3(round(clamp(
                                entityNormal,
                                vec3(-1.0),
                                vec3(1.0)
                            ) * 32767.0));
                            result.distance = entityDistance;
                            result.steps = entityPackedUv;
                            return result;
                        }

                        if (sectionExit > maxDistance) break;
                        sectionEntry = max(sectionExit, 0.0);
                        if (tMax.x <= sectionExit + 0.00001) {
                            sectionCoord.x += step.x;
                            tMax.x += tDelta.x;
                        }
                        if (tMax.y <= sectionExit + 0.00001) {
                            sectionCoord.y += step.y;
                            tMax.y += tDelta.y;
                        }
                        if (tMax.z <= sectionExit + 0.00001) {
                            sectionCoord.z += step.z;
                            tMax.z += tDelta.z;
                        }
                    }
                    return result;
                }

                HitResult traceRayLimited(vec3 origin, vec3 direction, float maxDistance) {
                    HitResult staticHit = p17TraceStaticRayLimited(origin, direction, maxDistance);
                    float entityMaxDistance = staticHit.hit != 0u
                            ? min(maxDistance, staticHit.distance)
                            : maxDistance;
                    HitResult entityHit = p17TraceEntityRayLimited(
                        origin,
                        direction,
                        entityMaxDistance
                    );
                    if (entityHit.hit != 0u
                            && (staticHit.hit == 0u || entityHit.distance < staticHit.distance - 0.00001)) {
                        return entityHit;
                    }
                    return staticHit;
                }

                """).formatted(
                GpuDynamicEntityScene.FLAG_SPIDER_EYES,
                GpuDynamicEntityScene.MAX_ENTITIES,
                GpuDynamicEntityScene.SECTION_LOOKUP_CAPACITY,
                GpuDynamicEntityScene.SECTION_LOOKUP_CAPACITY - 1,
                GpuDynamicEntityScene.MAX_ENTITIES_PER_SECTION,
                GpuDynamicEntityScene.ENTITY_DESCRIPTOR_WORDS_PER_RECORD,
                GpuDynamicEntityScene.SECTION_BUCKET_WORDS,
                GpuDynamicEntityScene.ENTITY_DESCRIPTOR_BASE_WORD,
                GpuDynamicEntityScene.SECTION_LOOKUP_BASE_WORD,
                GpuDynamicEntityScene.SPIDER_EYE_POOL_BASE_WORD,
                GpuDynamicEntityScene.QUAD_POOL_BASE_WORD,
                GpuDynamicEntityScene.QUAD_WORDS_PER_RECORD,
                P14ModelMeshGpuLayout.MAX_STORAGE_WORDS
        );
        source = source.replace(traceEntryMarker, helpers + traceEntryMarker);

        String p15Marker = """
                        uint geometryCode = geometryAt(candidate.voxel);
                        vec4 optical = p15MaterialTransmission(candidate.materialId, geometryCode);
                """;
        String p15EntityOpaque = """
                        if (candidate.materialId == P17_ENTITY_MATERIAL_ID
                                || candidate.materialId == P17_SPIDER_ENTITY_MATERIAL_ID) {
                            candidate.distance += traveled;
                            result.hit = candidate;
                            return result;
                        }

                        uint geometryCode = geometryAt(candidate.voxel);
                        vec4 optical = p15MaterialTransmission(candidate.materialId, geometryCode);
                """;
        source = replaceRequiredOnce(
                source,
                p15Marker,
                p15EntityOpaque,
                "P15 entity opaque baseline"
        );

        String p18SyntheticMarker = """
                    // P14E/P17 synthetic materials intentionally keep their dedicated optics.
                    if (hit.materialId >= 0xFFF0u) return surface;
                """;
        String p18SpiderEmission = """
                    if (hit.materialId == P17_SPIDER_ENTITY_MATERIAL_ID) {
                        surface.albedo = materialColor(P17_ENTITY_MATERIAL_ID);
                        uint eyeArgb = p17SpiderEyeArgb(
                            p17EntitySceneBase(),
                            p17UnpackUv(hit.steps)
                        );
                        float eyeAlpha = float((eyeArgb >> 24u) & 255u) / 255.0;
                        surface.emission = eyeAlpha > 0.0
                                ? p17ArgbRgb(eyeArgb)
                                        * eyeAlpha
                                        * max(uintBitsToFloat(scene.data[89]), 0.0)
                                : vec3(0.0);
                        return surface;
                    }

                    // P14E/P17 synthetic materials intentionally keep their dedicated optics.
                    if (hit.materialId >= 0xFFF0u) return surface;
                """;
        source = replaceRequiredOnce(
                source,
                p18SyntheticMarker,
                p18SpiderEmission,
                "P18 spider eye emissive surface"
        );

        TotemLumenClient.LOGGER.info(
                "P17 dynamic entity shader tracing active: sectionBroadPhase={} buckets, candidatesPerSection={}, maxEntities={}, maxQuads={}, uv=true, spiderEyes=true, materialId=0x{}",
                GpuDynamicEntityScene.SECTION_LOOKUP_CAPACITY,
                GpuDynamicEntityScene.MAX_ENTITIES_PER_SECTION,
                GpuDynamicEntityScene.MAX_ENTITIES,
                GpuDynamicEntityScene.MAX_ENTITY_QUADS,
                Integer.toHexString(ENTITY_MATERIAL_ID).toUpperCase()
        );
        return source;
    }

    private static String replaceRequiredOnce(String source, String oldText, String newText, String label) {
        int first = source.indexOf(oldText);
        if (first < 0) {
            throw new IllegalStateException("P17 shader patch marker missing: " + label);
        }
        int second = source.indexOf(oldText, first + oldText.length());
        if (second >= 0) {
            throw new IllegalStateException("P17 shader patch marker is ambiguous: " + label);
        }
        return source.substring(0, first) + newText + source.substring(first + oldText.length());
    }
}
