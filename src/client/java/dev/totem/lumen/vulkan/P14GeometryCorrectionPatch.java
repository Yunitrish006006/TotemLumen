package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;

/**
 * P14B correctness layer for stateful hinged geometry.
 *
 * <p>The first common-geometry pass encoded doors/trapdoors with the correct state bits but mapped
 * Minecraft's facing directions to the opposite block edge. This pass fixes that mapping, corrects
 * door hinge rotation, and adds fence-gate geometry without disturbing the already validated P14B
 * shader body.</p>
 */
final class P14GeometryCorrectionPatch {
    private P14GeometryCorrectionPatch() {
    }

    static String apply(String source) {
        source = replaceRequiredOnce(
                source,
                "                        || family == 0x6000u;",
                "                        || family == 0x6000u\n"
                        + "                        || family == 0x7000u;",
                "local-geometry family list"
        );

        String oldEdgeMapping = """
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
                """;
        String correctedEdgeMapping = """
                    // Minecraft's door/trapdoor facing-to-shape mapping is:
                    // N -> high-Z edge, E -> low-X edge, S -> low-Z edge, W -> high-X edge.
                    if (facing == 1u) {
                        localMin = vec3(0.0, 0.0, 0.0);
                        localMax = vec3(thickness, 1.0, 1.0);
                    } else if (facing == 2u) {
                        localMin = vec3(0.0, 0.0, 0.0);
                        localMax = vec3(1.0, 1.0, thickness);
                    } else if (facing == 3u) {
                        localMin = vec3(1.0 - thickness, 0.0, 0.0);
                        localMax = vec3(1.0, 1.0, 1.0);
                    } else {
                        localMin = vec3(0.0, 0.0, 1.0 - thickness);
                        localMax = vec3(1.0, 1.0, 1.0);
                    }
                """;
        source = replaceRequiredOnce(source, oldEdgeMapping, correctedEdgeMapping, "edge-panel facing mapping");

        source = replaceRequiredOnce(
                source,
                "                            if (open) panelFacing = (facing + (hingeRight ? 1u : 3u)) & 3u;",
                "                            if (open) panelFacing = (facing + (hingeRight ? 3u : 1u)) & 3u;",
                "door hinge rotation"
        );

        String familyTail = """
                        }
                    }

                    hitDistance = bestDistance;
                """;
        String correctedFamilyTail = """
                        } else if (family == 0x7000u) {
                            uint facing = params & 3u;
                            bool open = (params & 4u) != 0u;
                            bool inWall = (params & 8u) != 0u;
                            bool zAxis = facing == 0u || facing == 2u;

                            float postTop = inWall ? 0.8125 : 1.0;
                            float railShift = inWall ? -0.1875 : 0.0;
                            float lowerRailMin = max(0.0, 0.375 + railShift);
                            float lowerRailMax = max(0.125, 0.5625 + railShift);
                            float upperRailMin = max(0.1875, 0.6875 + railShift);
                            float upperRailMax = max(0.375, 0.875 + railShift);

                            if (zAxis) {
                                // Gate spans east-west. End posts line up with fences on both sides.
                                p14TryBox(origin, direction, voxel, vec3(0.0, 0.0, 0.375), vec3(0.1875, postTop, 0.625), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                                p14TryBox(origin, direction, voxel, vec3(0.8125, 0.0, 0.375), vec3(1.0, postTop, 0.625), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                                if (open) {
                                    // Fold both leaves against the end posts so the center passage is open.
                                    p14TryBox(origin, direction, voxel, vec3(0.0625, lowerRailMin, 0.0), vec3(0.1875, lowerRailMax, 1.0), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                                    p14TryBox(origin, direction, voxel, vec3(0.8125, lowerRailMin, 0.0), vec3(0.9375, lowerRailMax, 1.0), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                                    p14TryBox(origin, direction, voxel, vec3(0.0625, upperRailMin, 0.0), vec3(0.1875, upperRailMax, 1.0), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                                    p14TryBox(origin, direction, voxel, vec3(0.8125, upperRailMin, 0.0), vec3(0.9375, upperRailMax, 1.0), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                                } else {
                                    p14TryBox(origin, direction, voxel, vec3(0.1875, lowerRailMin, 0.4375), vec3(0.8125, lowerRailMax, 0.5625), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                                    p14TryBox(origin, direction, voxel, vec3(0.1875, upperRailMin, 0.4375), vec3(0.8125, upperRailMax, 0.5625), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                                }
                            } else {
                                // Gate spans north-south. End posts line up with fences on both sides.
                                p14TryBox(origin, direction, voxel, vec3(0.375, 0.0, 0.0), vec3(0.625, postTop, 0.1875), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                                p14TryBox(origin, direction, voxel, vec3(0.375, 0.0, 0.8125), vec3(0.625, postTop, 1.0), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                                if (open) {
                                    p14TryBox(origin, direction, voxel, vec3(0.0, lowerRailMin, 0.0625), vec3(1.0, lowerRailMax, 0.1875), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                                    p14TryBox(origin, direction, voxel, vec3(0.0, lowerRailMin, 0.8125), vec3(1.0, lowerRailMax, 0.9375), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                                    p14TryBox(origin, direction, voxel, vec3(0.0, upperRailMin, 0.0625), vec3(1.0, upperRailMax, 0.1875), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                                    p14TryBox(origin, direction, voxel, vec3(0.0, upperRailMin, 0.8125), vec3(1.0, upperRailMax, 0.9375), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                                } else {
                                    p14TryBox(origin, direction, voxel, vec3(0.4375, lowerRailMin, 0.1875), vec3(0.5625, lowerRailMax, 0.8125), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                                    p14TryBox(origin, direction, voxel, vec3(0.4375, upperRailMin, 0.1875), vec3(0.5625, upperRailMax, 0.8125), cellEntryDistance, cellExitDistance, found, bestDistance, bestNormal);
                                }
                            }
                        }
                    }

                    hitDistance = bestDistance;
                """;
        source = replaceRequiredOnce(source, familyTail, correctedFamilyTail, "fence-gate geometry branch");

        TotemLumenClient.LOGGER.info(
                "P14B geometry corrections active: doorFacing=true, doorHinge=true, trapdoorFacing=true, fenceGate=true"
        );
        return source;
    }

    private static String replaceRequiredOnce(String source, String oldText, String newText, String label) {
        int first = source.indexOf(oldText);
        if (first < 0) {
            throw new IllegalStateException("P14B correction marker missing: " + label);
        }
        int second = source.indexOf(oldText, first + oldText.length());
        if (second >= 0) {
            throw new IllegalStateException("P14B correction marker is ambiguous: " + label);
        }
        return source.substring(0, first) + newText + source.substring(first + oldText.length());
    }
}
