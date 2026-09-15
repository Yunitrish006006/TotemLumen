package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;

/**
 * P14B extension layered after the proven P14A slab transform.
 *
 * <p>Voxel DDA remains the broad phase. Common non-cube Minecraft blocks are represented as small
 * unions of block-local AABBs, so primary rays, shadow rays, skylight visibility and GI bounce rays
 * all consume the same geometry without increasing voxel resolution.</p>
 */
final class P14CommonGeometryPatch {
    private P14CommonGeometryPatch() {
    }

    static String apply(String source) {
        String geometryStartMarker = "bool p14IntersectVoxelGeometry(";
        String traceStartMarker = "HitResult traceRayLimited(vec3 origin, vec3 direction, float maxDistance) {";
        int geometryStart = source.indexOf(geometryStartMarker);
        int traceStart = source.indexOf(traceStartMarker, geometryStart);
        if (geometryStart < 0 || traceStart < 0 || traceStart <= geometryStart) {
            throw new IllegalStateException("P14B geometry patch marker missing: local geometry span");
        }

        String geometryFunctions = """
                bool p14UsesLocalGeometry(uint geometryCode) {
                    if (geometryCode == 1u || geometryCode == 2u) return true;
                    uint family = geometryCode & 0xF000u;
                    return family == 0x1000u
                        || family == 0x2000u
                        || family == 0x3000u
                        || family == 0x4000u
                        || family == 0x5000u
                        || family == 0x6000u;
                }

                void p14RotateNorthAabb(
                        vec3 sourceMin,
                        vec3 sourceMax,
                        uint facing,
                        out vec3 rotatedMin,
                        out vec3 rotatedMax
                ) {
                    if (facing == 1u) {
                        rotatedMin = vec3(1.0 - sourceMax.z, sourceMin.y, sourceMin.x);
                        rotatedMax = vec3(1.0 - sourceMin.z, sourceMax.y, sourceMax.x);
                    } else if (facing == 2u) {
                        rotatedMin = vec3(1.0 - sourceMax.x, sourceMin.y, 1.0 - sourceMax.z);
                        rotatedMax = vec3(1.0 - sourceMin.x, sourceMax.y, 1.0 - sourceMin.z);
                    } else if (facing == 3u) {
                        rotatedMin = vec3(sourceMin.z, sourceMin.y, 1.0 - sourceMax.x);
                        rotatedMax = vec3(sourceMax.z, sourceMax.y, 1.0 - sourceMin.x);
                    } else {
                        rotatedMin = sourceMin;
                        rotatedMax = sourceMax;
                    }
                }

                void p14TryBox(
                        vec3 origin,
                        vec3 direction,
                        ivec3 voxel,
                        vec3 localMin,
                        vec3 localMax,
                        float cellEntryDistance,
                        float cellExitDistance,
                        inout bool found,
                        inout float bestDistance,
                        inout ivec3 bestNormal
                ) {
                    float candidateDistance;
                    ivec3 candidateNormal;
                    vec3 voxelOrigin = vec3(voxel);
                    if (p14IntersectAabb(
                            origin,
                            direction,
                            voxelOrigin + localMin,
                            voxelOrigin + localMax,
                            cellEntryDistance,
                            cellExitDistance,
                            candidateDistance,
                            candidateNormal
                    )) {
                        if (!found || candidateDistance < bestDistance) {
                            found = true;
                            bestDistance = candidateDistance;
                            bestNormal = candidateNormal;
                        }
                    }
                }

                void p14TryRotatedBox(
                        vec3 origin,
                        vec3 direction,
                        ivec3 voxel,
                        vec3 northMin,
                        vec3 northMax,
                        uint facing,
                        float cellEntryDistance,
                        float cellExitDistance,
                        inout bool found,
                        inout float bestDistance,
                        inout ivec3 bestNormal
                ) {
                    vec3 localMin;
                    vec3 localMax;
                    p14RotateNorthAabb(northMin, northMax, facing, localMin, localMax);
                    p14TryBox(
                        origin, direction, voxel, localMin, localMax,
                        cellEntryDistance, cellExitDistance,
                        found, bestDistance, bestNormal
                    );
                }

                float p14WallHeight(uint sideState) {
                    return sideState == 2u ? 1.0 : 0.8125;
                }

                void p14TryEdgePanel(
                        vec3 origin,
                        vec3 direction,
                        ivec3 voxel,
                        uint facing,
                        float thickness,
                        float cellEntryDistance,
                        float cellExitDistance,
                        inout bool found,
                        inout float bestDistance,
                        inout ivec3 bestNormal
                ) {
                    vec3 localMin;
                    vec3 localMax;
                    if (facing == 1u) {
                        localMin = vec3(1.0 - thickness, 0.0, 0.0);
                        localMax = vec3(1.0, 1.0, 1.0);
                    } else if (facing == 2u) {
                        localMin = vec3(0.0, 0.0, 1.0 - thickness);
                        localMax = vec3(1.0, 1.0, 1.0);
                    } else if (facing == 3u) {
                        localMin = vec3(0.0, 0.0, 0.0);
                        localMax = vec3(thickness, 1.0, 1.0);
                    } else {
                        localMin = vec3(0.0, 0.0, 0.0);
                        localMax = vec3(1.0, 1.0, thickness);
                    }
                    p14TryBox(
                        origin, direction, voxel, localMin, localMax,
                        cellEntryDistance, cellExitDistance,
                        found, bestDistance, bestNormal
                    );
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
                    bool found = false;
                    float bestDistance = 1.0e30;
                    ivec3 bestNormal = ivec3(0);

                    if (geometryCode == 1u) {
                        p14TryBox(
                            origin, direction, voxel,
                            vec3(0.0, 0.0, 0.0), vec3(1.0, 0.5, 1.0),
                            cellEntryDistance, cellExitDistance,
                            found, bestDistance, bestNormal
                        );
                    } else if (geometryCode == 2u) {
                        p14TryBox(
                            origin, direction, voxel,
                            vec3(0.0, 0.5, 0.0), vec3(1.0, 1.0, 1.0),
                            cellEntryDistance, cellExitDistance,
                            found, bestDistance, bestNormal
                        );
                    } else {
                        uint family = geometryCode & 0xF000u;
                        uint params = geometryCode & 0x0FFFu;

                        if (family == 0x1000u) {
                            uint facing = params & 3u;
                            bool top = (params & 4u) != 0u;
                            uint shape = (params >> 3u) & 7u;
                            float baseMinY = top ? 0.5 : 0.0;
                            float baseMaxY = top ? 1.0 : 0.5;
                            float stepMinY = top ? 0.0 : 0.5;
                            float stepMaxY = top ? 0.5 : 1.0;

                            p14TryBox(
                                origin, direction, voxel,
                                vec3(0.0, baseMinY, 0.0), vec3(1.0, baseMaxY, 1.0),
                                cellEntryDistance, cellExitDistance,
                                found, bestDistance, bestNormal
                            );

                            if (shape == 3u) {
                                p14TryRotatedBox(
                                    origin, direction, voxel,
                                    vec3(0.0, stepMinY, 0.0), vec3(0.5, stepMaxY, 0.5), facing,
                                    cellEntryDistance, cellExitDistance,
                                    found, bestDistance, bestNormal
                                );
                            } else if (shape == 4u) {
                                p14TryRotatedBox(
                                    origin, direction, voxel,
                                    vec3(0.5, stepMinY, 0.0), vec3(1.0, stepMaxY, 0.5), facing,
                                    cellEntryDistance, cellExitDistance,
                                    found, bestDistance, bestNormal
                                );
                            } else {
                                p14TryRotatedBox(
                                    origin, direction, voxel,
                                    vec3(0.0, stepMinY, 0.0), vec3(1.0, stepMaxY, 0.5), facing,
                                    cellEntryDistance, cellExitDistance,
                                    found, bestDistance, bestNormal
                                );
                                if (shape == 1u) {
                                    p14TryRotatedBox(
                                        origin, direction, voxel,
                                        vec3(0.0, stepMinY, 0.5), vec3(0.5, stepMaxY, 1.0), facing,
                                        cellEntryDistance, cellExitDistance,
                                        found, bestDistance, bestNormal
                                    );
                                } else if (shape == 2u) {
                                    p14TryRotatedBox(
                                        origin, direction, voxel,
                                        vec3(0.5, stepMinY, 0.5), vec3(1.0, stepMaxY, 1.0), facing,
                                        cellEntryDistance, cellExitDistance,
                                        found, bestDistance, bestNormal
                                    );
                                }
                            }
                        } else if (family == 0x2000u) {
                            uint connections = params & 0xFu;
                            p14TryBox(
                                origin, direction, voxel,
                                vec3(0.375, 0.0, 0.375), vec3(0.625, 1.0, 0.625),
                                cellEntryDistance, cellExitDistance,
                                found, bestDistance, bestNormal
                            );
                            if ((connections & 1u) != 0u) {
                                p14TryBox(origin, direction, voxel, vec3(0.4375, 0.375, 0.0), vec3(0.5625, 0.5625, 0.5), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                                p14TryBox(origin, direction, voxel, vec3(0.4375, 0.75, 0.0), vec3(0.5625, 0.9375, 0.5), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                            }
                            if ((connections & 2u) != 0u) {
                                p14TryBox(origin, direction, voxel, vec3(0.5, 0.375, 0.4375), vec3(1.0, 0.5625, 0.5625), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                                p14TryBox(origin, direction, voxel, vec3(0.5, 0.75, 0.4375), vec3(1.0, 0.9375, 0.5625), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                            }
                            if ((connections & 4u) != 0u) {
                                p14TryBox(origin, direction, voxel, vec3(0.4375, 0.375, 0.5), vec3(0.5625, 0.5625, 1.0), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                                p14TryBox(origin, direction, voxel, vec3(0.4375, 0.75, 0.5), vec3(0.5625, 0.9375, 1.0), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                            }
                            if ((connections & 8u) != 0u) {
                                p14TryBox(origin, direction, voxel, vec3(0.0, 0.375, 0.4375), vec3(0.5, 0.5625, 0.5625), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                                p14TryBox(origin, direction, voxel, vec3(0.0, 0.75, 0.4375), vec3(0.5, 0.9375, 0.5625), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                            }
                        } else if (family == 0x3000u) {
                            uint north = params & 3u;
                            uint east = (params >> 2u) & 3u;
                            uint south = (params >> 4u) & 3u;
                            uint west = (params >> 6u) & 3u;
                            bool up = (params & 0x100u) != 0u;
                            if (up) {
                                p14TryBox(origin, direction, voxel, vec3(0.25, 0.0, 0.25), vec3(0.75, 1.0, 0.75), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                            }
                            if (north != 0u) p14TryBox(origin, direction, voxel, vec3(0.3125, 0.0, 0.0), vec3(0.6875, p14WallHeight(north), 0.5), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                            if (east != 0u) p14TryBox(origin, direction, voxel, vec3(0.5, 0.0, 0.3125), vec3(1.0, p14WallHeight(east), 0.6875), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                            if (south != 0u) p14TryBox(origin, direction, voxel, vec3(0.3125, 0.0, 0.5), vec3(0.6875, p14WallHeight(south), 1.0), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                            if (west != 0u) p14TryBox(origin, direction, voxel, vec3(0.0, 0.0, 0.3125), vec3(0.5, p14WallHeight(west), 0.6875), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                        } else if (family == 0x4000u) {
                            uint connections = params & 0xFu;
                            p14TryBox(origin, direction, voxel, vec3(0.4375, 0.0, 0.4375), vec3(0.5625, 1.0, 0.5625), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                            if ((connections & 1u) != 0u) p14TryBox(origin, direction, voxel, vec3(0.4375, 0.0, 0.0), vec3(0.5625, 1.0, 0.5), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                            if ((connections & 2u) != 0u) p14TryBox(origin, direction, voxel, vec3(0.5, 0.0, 0.4375), vec3(1.0, 1.0, 0.5625), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                            if ((connections & 4u) != 0u) p14TryBox(origin, direction, voxel, vec3(0.4375, 0.0, 0.5), vec3(0.5625, 1.0, 1.0), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                            if ((connections & 8u) != 0u) p14TryBox(origin, direction, voxel, vec3(0.0, 0.0, 0.4375), vec3(0.5, 1.0, 0.5625), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                        } else if (family == 0x5000u) {
                            uint facing = params & 3u;
                            bool open = (params & 4u) != 0u;
                            bool hingeRight = (params & 8u) != 0u;
                            uint panelFacing = facing;
                            if (open) panelFacing = (facing + (hingeRight ? 1u : 3u)) & 3u;
                            p14TryEdgePanel(
                                origin, direction, voxel, panelFacing, 0.1875,
                                cellEntryDistance, cellExitDistance,
                                found, bestDistance, bestNormal
                            );
                        } else if (family == 0x6000u) {
                            uint facing = params & 3u;
                            bool open = (params & 4u) != 0u;
                            bool top = (params & 8u) != 0u;
                            if (open) {
                                p14TryEdgePanel(
                                    origin, direction, voxel, facing, 0.1875,
                                    cellEntryDistance, cellExitDistance,
                                    found, bestDistance, bestNormal
                                );
                            } else if (top) {
                                p14TryBox(origin, direction, voxel, vec3(0.0, 0.8125, 0.0), vec3(1.0, 1.0, 1.0), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                            } else {
                                p14TryBox(origin, direction, voxel, vec3(0.0, 0.0, 0.0), vec3(1.0, 0.1875, 1.0), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                            }
                        }
                    }

                    hitDistance = bestDistance;
                    hitNormal = bestNormal;
                    return found;
                }

                """;

        source = source.substring(0, geometryStart) + geometryFunctions + source.substring(traceStart);

        String oldLocalBranch = "if (geometryCode == 1u || geometryCode == 2u) {";
        String newLocalBranch = "if (p14UsesLocalGeometry(geometryCode)) {";
        if (!source.contains(oldLocalBranch)) {
            throw new IllegalStateException("P14B geometry patch marker missing: trace local branch");
        }
        source = source.replace(oldLocalBranch, newLocalBranch);

        TotemLumenClient.LOGGER.info(
                "P14B common geometry active: stairs=true, fence=true, wall=true, pane=true, door=true, trapdoor=true, primitiveModel=block-local-aabb-union"
        );
        return source;
    }
}
