package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.geometry.BlockModelMeshRegistry;

/**
 * P14C shader transform for arbitrary block-local quad meshes emitted by Minecraft/Fabric models.
 *
 * <p>The existing voxel DDA remains the broad phase. MODEL_MESH voxels use a per-mesh descriptor
 * and a shared float vertex pool appended after the scene pixel words. Each quad is intersected as
 * two two-sided triangles, preserving sloped/diagonal model normals for lighting and reflection.</p>
 */
final class P14GenericModelMeshPatch {
    private P14GenericModelMeshPatch() {
    }

    static String apply(String source) {
        source = replaceRequiredOnce(
                source,
                "|| family == 0x7000u;",
                "|| family == 0x7000u\n        || family == 0xA000u;",
                "local geometry family list"
        );

        String geometryMarker = "bool p14IntersectVoxelGeometry(\n";
        if (!source.contains(geometryMarker)) {
            throw new IllegalStateException("P14C generic model marker missing: p14IntersectVoxelGeometry");
        }

        String helpers = """
                uint p14ModelDescriptorBase() {
                    uint pixelCount = scene.data[4] * scene.data[5];
                    return scene.data[3] + pixelCount;
                }

                uint p14ModelQuadBase() {
                    return p14ModelDescriptorBase() + 8192u;
                }

                vec3 p14ModelVertex(uint wordBase) {
                    return vec3(
                        uintBitsToFloat(scene.data[wordBase]),
                        uintBitsToFloat(scene.data[wordBase + 1u]),
                        uintBitsToFloat(scene.data[wordBase + 2u])
                    );
                }

                void p14TryTriangle(
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
                    bestNormal = ivec3(round(clamp(geometricNormal, vec3(-1.0), vec3(1.0)) * 32767.0));
                }

                void p14TryModelMesh(
                        vec3 origin,
                        vec3 direction,
                        ivec3 voxel,
                        uint meshId,
                        float cellEntryDistance,
                        float cellExitDistance,
                        inout bool found,
                        inout float bestDistance,
                        inout ivec3 bestNormal
                ) {
                    if (meshId == 0u || meshId > 4095u) return;
                    uint descriptor = p14ModelDescriptorBase() + meshId * 2u;
                    uint firstQuad = scene.data[descriptor];
                    uint quadCount = min(scene.data[descriptor + 1u], 512u);
                    if (quadCount == 0u) return;

                    vec3 blockOrigin = vec3(voxel);
                    uint quadPool = p14ModelQuadBase();
                    for (uint quadIndex = 0u; quadIndex < quadCount; quadIndex++) {
                        uint quadWord = quadPool + (firstQuad + quadIndex) * 12u;
                        vec3 v0 = blockOrigin + p14ModelVertex(quadWord);
                        vec3 v1 = blockOrigin + p14ModelVertex(quadWord + 3u);
                        vec3 v2 = blockOrigin + p14ModelVertex(quadWord + 6u);
                        vec3 v3 = blockOrigin + p14ModelVertex(quadWord + 9u);
                        p14TryTriangle(
                            origin, direction, v0, v1, v2,
                            cellEntryDistance, cellExitDistance,
                            found, bestDistance, bestNormal
                        );
                        p14TryTriangle(
                            origin, direction, v0, v2, v3,
                            cellEntryDistance, cellExitDistance,
                            found, bestDistance, bestNormal
                        );
                    }
                }

                """;
        source = source.replace(geometryMarker, helpers + geometryMarker);

        source = replaceRequiredOnce(
                source,
                "                    hitDistance = bestDistance;",
                """
                                    if ((geometryCode & 0xF000u) == 0xA000u) {
                                        p14TryModelMesh(
                                            origin,
                                            direction,
                                            voxel,
                                            geometryCode & 0x0FFFu,
                                            cellEntryDistance,
                                            cellExitDistance,
                                            found,
                                            bestDistance,
                                            bestNormal
                                        );
                                    }

                                    hitDistance = bestDistance;
                """.stripTrailing(),
                "generic mesh intersection"
        );

        source = replaceRequiredOnce(
                source,
                "return packRgba(vec3(hit.normal) * 0.5 + 0.5, 255u);",
                "return packRgba(normalize(vec3(hit.normal)) * 0.5 + 0.5, 255u);",
                "normal debug visualization"
        );

        TotemLumenClient.LOGGER.info(
                "P14C generic block-model geometry active: meshIds={}, maxQuads={}, maxQuadsPerMesh={}, source=FabricBlockStateModel",
                BlockModelMeshRegistry.MAX_MESH_ID,
                BlockModelMeshRegistry.MAX_QUADS,
                BlockModelMeshRegistry.MAX_QUADS_PER_MESH
        );
        return source;
    }

    private static String replaceRequiredOnce(String source, String oldText, String newText, String label) {
        int first = source.indexOf(oldText);
        if (first < 0) {
            throw new IllegalStateException("P14C generic model patch marker missing: " + label);
        }
        int second = source.indexOf(oldText, first + oldText.length());
        if (second >= 0) {
            throw new IllegalStateException("P14C generic model patch marker is ambiguous: " + label);
        }
        return source.substring(0, first) + newText + source.substring(first + oldText.length());
    }
}
