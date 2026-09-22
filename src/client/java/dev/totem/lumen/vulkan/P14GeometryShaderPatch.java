package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;

/**
 * P14A runtime shader transform for packed voxel geometry and block-local slab intersection.
 *
 * <p>The established DDA remains the broad phase. Full cubes keep the original immediate-hit fast
 * path. Only slab candidates execute a local AABB intersection inside the current voxel, allowing a
 * ray to pass through the empty half and continue traversal without increasing voxel resolution.</p>
 */
final class P14GeometryShaderPatch {
    static final int MATERIAL_MASK = 0xFFFF;
    static final int GEOMETRY_FULL_CUBE = 0;
    static final int GEOMETRY_SLAB_BOTTOM = 1;
    static final int GEOMETRY_SLAB_TOP = 2;

    private P14GeometryShaderPatch() {
    }

    static String apply(String source) {
        String materialStartMarker = "uint materialAt(ivec3 voxel) {";
        String traceStartMarker = "HitResult traceRayLimited(vec3 origin, vec3 direction, float maxDistance) {";
        int materialStart = source.indexOf(materialStartMarker);
        int traceStart = source.indexOf(traceStartMarker, materialStart);
        if (materialStart < 0 || traceStart < 0 || traceStart <= materialStart) {
            throw new IllegalStateException("P14 geometry shader patch marker missing: material/trace boundary");
        }

        String voxelHelpers = """
                uint voxelWordAtSection(ivec3 voxel, ivec3 sectionCoord, int slot) {
                    if (slot < 0) return 0u;

                    ivec3 local = voxel - sectionCoord * 16;
                    uint index = uint((local.y << 8) | (local.z << 4) | local.x);
                    uint voxelBase = VOXEL_BASE + uint(slot) * VOXELS_PER_SECTION;
                    return scene.data[voxelBase + index];
                }

                uint voxelWordAt(ivec3 voxel) {
                    ivec3 sectionCoord = ivec3(
                        floorDiv16(voxel.x),
                        floorDiv16(voxel.y),
                        floorDiv16(voxel.z)
                    );
                    int slot = findSectionSlot(sectionCoord);
                    return voxelWordAtSection(voxel, sectionCoord, slot);
                }

                uint materialAt(ivec3 voxel) {
                    return voxelWordAt(voxel) & 0xFFFFu;
                }

                uint geometryAt(ivec3 voxel) {
                    return voxelWordAt(voxel) >> 16u;
                }

                bool p14IntersectAabb(
                        vec3 origin,
                        vec3 direction,
                        vec3 boundsMin,
                        vec3 boundsMax,
                        float minDistance,
                        float maxDistance,
                        out float hitDistance,
                        out ivec3 hitNormal
                ) {
                    float nearDistance = -1.0e30;
                    float farDistance = 1.0e30;
                    ivec3 nearNormal = ivec3(0);

                    if (abs(direction.x) < 0.000001) {
                        if (origin.x < boundsMin.x || origin.x > boundsMax.x) return false;
                    } else {
                        float t0 = (boundsMin.x - origin.x) / direction.x;
                        float t1 = (boundsMax.x - origin.x) / direction.x;
                        float axisNear = min(t0, t1);
                        float axisFar = max(t0, t1);
                        if (axisNear > nearDistance) {
                            nearDistance = axisNear;
                            nearNormal = direction.x > 0.0 ? ivec3(-1, 0, 0) : ivec3(1, 0, 0);
                        }
                        farDistance = min(farDistance, axisFar);
                    }

                    if (abs(direction.y) < 0.000001) {
                        if (origin.y < boundsMin.y || origin.y > boundsMax.y) return false;
                    } else {
                        float t0 = (boundsMin.y - origin.y) / direction.y;
                        float t1 = (boundsMax.y - origin.y) / direction.y;
                        float axisNear = min(t0, t1);
                        float axisFar = max(t0, t1);
                        if (axisNear > nearDistance) {
                            nearDistance = axisNear;
                            nearNormal = direction.y > 0.0 ? ivec3(0, -1, 0) : ivec3(0, 1, 0);
                        }
                        farDistance = min(farDistance, axisFar);
                    }

                    if (abs(direction.z) < 0.000001) {
                        if (origin.z < boundsMin.z || origin.z > boundsMax.z) return false;
                    } else {
                        float t0 = (boundsMin.z - origin.z) / direction.z;
                        float t1 = (boundsMax.z - origin.z) / direction.z;
                        float axisNear = min(t0, t1);
                        float axisFar = max(t0, t1);
                        if (axisNear > nearDistance) {
                            nearDistance = axisNear;
                            nearNormal = direction.z > 0.0 ? ivec3(0, 0, -1) : ivec3(0, 0, 1);
                        }
                        farDistance = min(farDistance, axisFar);
                    }

                    float candidate = max(nearDistance, minDistance);
                    if (farDistance + 0.00001 < candidate) return false;
                    if (farDistance < -0.00001) return false;
                    if (candidate > maxDistance + 0.00001) return false;

                    hitDistance = max(candidate, 0.0);
                    hitNormal = nearDistance < minDistance - 0.0001 ? ivec3(0) : nearNormal;
                    return true;
                }

                bool p14IntersectVoxelGeometry(
                        vec3 origin,
                        vec3 direction,
                        ivec3 voxel,
                        uint geometryCode,
                        float cellEntryDistance,
                        float cellExitDistance,
                        out float hitDistance,
                        out ivec3 hitNormal
                ) {
                    vec3 localMin;
                    vec3 localMax;
                    if (geometryCode == 1u) {
                        localMin = vec3(0.0, 0.0, 0.0);
                        localMax = vec3(1.0, 0.5, 1.0);
                    } else if (geometryCode == 2u) {
                        localMin = vec3(0.0, 0.5, 0.0);
                        localMax = vec3(1.0, 1.0, 1.0);
                    } else {
                        return false;
                    }
                    vec3 voxelOrigin = vec3(voxel);
                    return p14IntersectAabb(
                        origin,
                        direction,
                        voxelOrigin + localMin,
                        voxelOrigin + localMax,
                        cellEntryDistance,
                        cellExitDistance,
                        hitDistance,
                        hitNormal
                    );
                }

                """;

        source = source.substring(0, materialStart) + voxelHelpers + source.substring(traceStart);

        traceStart = source.indexOf(traceStartMarker);
        String traceEndMarker = "HitResult traceRay(vec3 origin, vec3 direction) {";
        int traceEnd = source.indexOf(traceEndMarker, traceStart);
        if (traceStart < 0 || traceEnd < 0 || traceEnd <= traceStart) {
            throw new IllegalStateException("P14 geometry shader patch marker missing: traceRayLimited span");
        }

        String traceReplacement = """
                HitResult traceRayLimited(vec3 origin, vec3 direction, float maxDistance) {
                    HitResult result;
                    result.hit = 0u;
                    result.materialId = 0u;
                    result.voxel = ivec3(0);
                    result.normal = ivec3(0);
                    result.distance = 0.0;
                    result.steps = 0u;

                    float directionLength = length(direction);
                    if (directionLength < 0.000001 || maxDistance <= 0.0) return result;

                    vec3 dir = direction / directionLength;
                    ivec3 voxel = ivec3(floor(origin));
                    ivec3 step = ivec3(
                        dir.x > 0.0 ? 1 : (dir.x < 0.0 ? -1 : 0),
                        dir.y > 0.0 ? 1 : (dir.y < 0.0 ? -1 : 0),
                        dir.z > 0.0 ? 1 : (dir.z < 0.0 ? -1 : 0)
                    );
                    const float INF = 1.0e30;
                    vec3 tDelta = vec3(
                        step.x == 0 ? INF : abs(1.0 / dir.x),
                        step.y == 0 ? INF : abs(1.0 / dir.y),
                        step.z == 0 ? INF : abs(1.0 / dir.z)
                    );
                    vec3 tMax = vec3(
                        step.x > 0 ? (float(voxel.x + 1) - origin.x) / dir.x : (step.x < 0 ? (float(voxel.x) - origin.x) / dir.x : INF),
                        step.y > 0 ? (float(voxel.y + 1) - origin.y) / dir.y : (step.y < 0 ? (float(voxel.y) - origin.y) / dir.y : INF),
                        step.z > 0 ? (float(voxel.z + 1) - origin.z) / dir.z : (step.z < 0 ? (float(voxel.z) - origin.z) / dir.z : INF)
                    );

                    ivec3 normal = ivec3(0);
                    float distance = 0.0;
                    uint maxSteps = scene.data[6];

                    // Cache the static section lookup for the whole 16x16x16 section. The old
                    // path recomputed floorDiv16 + sectionHash + open-address probing for every
                    // voxel step of every primary/GI/shadow/transmission ray.
                    ivec3 sectionCoord = ivec3(
                        floorDiv16(voxel.x),
                        floorDiv16(voxel.y),
                        floorDiv16(voxel.z)
                    );
                    int sectionSlot = findSectionSlot(sectionCoord);

                    for (uint iteration = 0u; iteration < maxSteps; iteration++) {
                        uint voxelWord = voxelWordAtSection(voxel, sectionCoord, sectionSlot);
                        uint materialId = voxelWord & 0xFFFFu;
                        if (materialId != 0u) {
                            uint geometryCode = voxelWord >> 16u;
                            if (geometryCode == 1u || geometryCode == 2u) {
                                float cellExitDistance = min(tMax.x, min(tMax.y, tMax.z));
                                float shapeDistance;
                                ivec3 shapeNormal;
                                if (p14IntersectVoxelGeometry(
                                        origin,
                                        dir,
                                        voxel,
                                        geometryCode,
                                        distance,
                                        min(cellExitDistance, maxDistance),
                                        shapeDistance,
                                        shapeNormal
                                )) {
                                    result.hit = 1u;
                                    result.materialId = materialId;
                                    result.voxel = voxel;
                                    result.normal = shapeNormal;
                                    result.distance = shapeDistance;
                                    result.steps = iteration;
                                    return result;
                                }
                            } else {
                                result.hit = 1u;
                                result.materialId = materialId;
                                result.voxel = voxel;
                                result.normal = normal;
                                result.distance = distance;
                                result.steps = iteration;
                                return result;
                            }
                        }

                        if (tMax.x <= tMax.y && tMax.x <= tMax.z) {
                            distance = tMax.x;
                            if (distance > maxDistance) break;
                            voxel.x += step.x;
                            normal = ivec3(-step.x, 0, 0);
                            tMax.x += tDelta.x;
                            if ((step.x > 0 && voxel.x == (sectionCoord.x + 1) * 16)
                                    || (step.x < 0 && voxel.x == sectionCoord.x * 16 - 1)) {
                                sectionCoord.x += step.x;
                                sectionSlot = findSectionSlot(sectionCoord);
                            }
                        } else if (tMax.y <= tMax.z) {
                            distance = tMax.y;
                            if (distance > maxDistance) break;
                            voxel.y += step.y;
                            normal = ivec3(0, -step.y, 0);
                            tMax.y += tDelta.y;
                            if ((step.y > 0 && voxel.y == (sectionCoord.y + 1) * 16)
                                    || (step.y < 0 && voxel.y == sectionCoord.y * 16 - 1)) {
                                sectionCoord.y += step.y;
                                sectionSlot = findSectionSlot(sectionCoord);
                            }
                        } else {
                            distance = tMax.z;
                            if (distance > maxDistance) break;
                            voxel.z += step.z;
                            normal = ivec3(0, 0, -step.z);
                            tMax.z += tDelta.z;
                            if ((step.z > 0 && voxel.z == (sectionCoord.z + 1) * 16)
                                    || (step.z < 0 && voxel.z == sectionCoord.z * 16 - 1)) {
                                sectionCoord.z += step.z;
                                sectionSlot = findSectionSlot(sectionCoord);
                            }
                        }
                    }

                    result.voxel = voxel;
                    result.normal = normal;
                    result.distance = distance;
                    result.steps = maxSteps;
                    return result;
                }

                """;

        source = source.substring(0, traceStart) + traceReplacement + source.substring(traceEnd);

        TotemLumenClient.LOGGER.info(
                "P14 block-local geometry shader patch active: packedVoxelBits=material16+geometry16, fullCubeFastPath=true, sectionSlotCache=true, slabBottom=true, slabTop=true"
        );
        return source;
    }
}
