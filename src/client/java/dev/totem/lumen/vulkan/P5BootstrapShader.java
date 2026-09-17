package dev.totem.lumen.vulkan;

/**
 * Tiny renderer-readiness compute shader for Vulkan/MoltenVK cold starts.
 *
 * <p>This deliberately does not contain P12 GI, P13 environment lighting, P14 model meshes,
 * P15 transmission, P16 reflection or P17 entities. It exists only so Totem Lumen can prove
 * that Vulkan compute submission/composition works without making a multi-minute Metal pipeline
 * compile part of the renderer readiness gate. Full rendering pipelines replace it asynchronously.
 */
public final class P5BootstrapShader {
    private P5BootstrapShader() {
    }

    public static String source() {
        return """
                #version 450
                layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
                layout(set = 0, binding = 0, std430) buffer SceneBuffer {
                    uint data[];
                } scene;

                const uint LOOKUP_BASE = 64u;
                const uint LOOKUP_CAPACITY = 128u;
                const uint LOOKUP_MASK = 127u;
                const uint LOOKUP_WORDS_PER_BUCKET = 4u;
                const uint VOXEL_BASE = 576u;
                const uint VOXELS_PER_SECTION = 4096u;

                struct HitResult {
                    uint hit;
                    uint materialId;
                    ivec3 voxel;
                    ivec3 normal;
                    float distance;
                    uint steps;
                };

                int floorDiv16(int value) {
                    return value >= 0 ? value / 16 : -((-value + 15) / 16);
                }

                uint sectionHash(ivec3 coord) {
                    uint h = uint(coord.x) * 0x9E3779B1u;
                    h ^= uint(coord.y) * 0x85EBCA77u;
                    h ^= uint(coord.z) * 0xC2B2AE3Du;
                    h ^= h >> 16u;
                    return h;
                }

                int findSectionSlot(ivec3 sectionCoord) {
                    uint start = sectionHash(sectionCoord) & LOOKUP_MASK;
                    for (uint probe = 0u; probe < LOOKUP_CAPACITY; probe++) {
                        uint bucket = (start + probe) & LOOKUP_MASK;
                        uint base = LOOKUP_BASE + bucket * LOOKUP_WORDS_PER_BUCKET;
                        uint slotPlusOne = scene.data[base + 3u];
                        if (slotPlusOne == 0u) return -1;
                        ivec3 candidate = ivec3(
                            int(scene.data[base]),
                            int(scene.data[base + 1u]),
                            int(scene.data[base + 2u])
                        );
                        if (all(equal(candidate, sectionCoord))) return int(slotPlusOne - 1u);
                    }
                    return -1;
                }

                uint materialAt(ivec3 voxel) {
                    ivec3 sectionCoord = ivec3(
                        floorDiv16(voxel.x),
                        floorDiv16(voxel.y),
                        floorDiv16(voxel.z)
                    );
                    int slot = findSectionSlot(sectionCoord);
                    if (slot < 0) return 0u;
                    ivec3 local = voxel - sectionCoord * 16;
                    uint index = uint((local.y << 8) | (local.z << 4) | local.x);
                    uint packedVoxel = scene.data[VOXEL_BASE + uint(slot) * VOXELS_PER_SECTION + index];
                    return packedVoxel & 0xFFFFu;
                }

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
                    for (uint iteration = 0u; iteration < maxSteps; iteration++) {
                        uint materialId = materialAt(voxel);
                        if (materialId != 0u) {
                            result.hit = 1u;
                            result.materialId = materialId;
                            result.voxel = voxel;
                            result.normal = normal;
                            result.distance = distance;
                            result.steps = iteration;
                            return result;
                        }

                        if (tMax.x <= tMax.y && tMax.x <= tMax.z) {
                            distance = tMax.x;
                            if (distance > maxDistance) break;
                            voxel.x += step.x;
                            normal = ivec3(-step.x, 0, 0);
                            tMax.x += tDelta.x;
                        } else if (tMax.y <= tMax.z) {
                            distance = tMax.y;
                            if (distance > maxDistance) break;
                            voxel.y += step.y;
                            normal = ivec3(0, -step.y, 0);
                            tMax.y += tDelta.y;
                        } else {
                            distance = tMax.z;
                            if (distance > maxDistance) break;
                            voxel.z += step.z;
                            normal = ivec3(0, 0, -step.z);
                            tMax.z += tDelta.z;
                        }
                    }
                    result.voxel = voxel;
                    result.normal = normal;
                    result.distance = distance;
                    result.steps = maxSteps;
                    return result;
                }

                uint packRgba(vec3 rgb, uint alpha) {
                    uvec3 c = uvec3(clamp(rgb, vec3(0.0), vec3(1.0)) * 255.0 + 0.5);
                    return c.r | (c.g << 8u) | (c.b << 16u) | ((alpha & 255u) << 24u);
                }

                vec3 materialColor(uint materialId) {
                    uint h = materialId * 1664525u + 1013904223u;
                    vec3 rgb = vec3(
                        float((h >> 0u) & 255u),
                        float((h >> 8u) & 255u),
                        float((h >> 16u) & 255u)
                    ) / 255.0;
                    return mix(vec3(0.18), rgb, 0.70);
                }

                vec3 bootstrapColor(HitResult hit, vec3 direction) {
                    if (hit.hit == 0u) {
                        float horizon = clamp(direction.y * 0.5 + 0.5, 0.0, 1.0);
                        return mix(vec3(0.025, 0.035, 0.055), vec3(0.16, 0.28, 0.42), horizon);
                    }
                    vec3 normal = vec3(hit.normal);
                    float facing = 0.72;
                    if (dot(normal, normal) > 0.5) {
                        normal = normalize(normal);
                        facing = 0.42 + 0.58 * max(dot(normal, normalize(vec3(0.42, 0.82, 0.38))), 0.0);
                    }
                    return materialColor(hit.materialId) * facing;
                }

                void main() {
                    uvec2 pixel = gl_GlobalInvocationID.xy;
                    uint width = scene.data[4];
                    uint height = scene.data[5];
                    if (pixel.x >= width || pixel.y >= height) return;

                    vec3 origin = vec3(
                        uintBitsToFloat(scene.data[8]),
                        uintBitsToFloat(scene.data[9]),
                        uintBitsToFloat(scene.data[10])
                    );
                    vec3 right = vec3(
                        uintBitsToFloat(scene.data[11]),
                        uintBitsToFloat(scene.data[12]),
                        uintBitsToFloat(scene.data[13])
                    );
                    vec3 up = vec3(
                        uintBitsToFloat(scene.data[14]),
                        uintBitsToFloat(scene.data[15]),
                        uintBitsToFloat(scene.data[16])
                    );
                    vec3 forward = vec3(
                        uintBitsToFloat(scene.data[17]),
                        uintBitsToFloat(scene.data[18]),
                        uintBitsToFloat(scene.data[19])
                    );
                    float tanHalfFov = uintBitsToFloat(scene.data[20]);
                    float aspect = uintBitsToFloat(scene.data[21]);
                    float ndcX = ((float(pixel.x) + 0.5) / float(width)) * 2.0 - 1.0;
                    float ndcY = 1.0 - ((float(pixel.y) + 0.5) / float(height)) * 2.0;
                    vec3 direction = normalize(
                        forward + right * (ndcX * aspect * tanHalfFov) + up * (ndcY * tanHalfFov)
                    );

                    HitResult hit = traceRayLimited(
                        origin,
                        direction,
                        uintBitsToFloat(scene.data[7])
                    );
                    uint pixelBase = scene.data[3];
                    scene.data[pixelBase + pixel.y * width + pixel.x] = packRgba(
                        bootstrapColor(hit, direction),
                        255u
                    );
                }
                """;
    }
}
