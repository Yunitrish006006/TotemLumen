package dev.totem.lumen.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.gpu.GpuSectionLightLists;
import dev.totem.lumen.gpu.GpuSectionLookupTable;
import dev.totem.lumen.gpu.GpuSectionSlotAllocator;
import dev.totem.lumen.integration.LabPbrTextureRegistry;
import dev.totem.lumen.integration.RendererRuntimeTuningRegistry;
import dev.totem.lumen.integration.SceneExtractionBridge;
import dev.totem.lumen.render.RendererSettings;
import dev.totem.lumen.scene.FrameSnapshot;
import dev.totem.lumen.scene.SectionKey;
import dev.totem.lumen.scene.SectionSnapshot;
import dev.totem.lumen.scene.SectionVoxelData;
import dev.totem.lumen.vulkan.resource.VulkanOwnedBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persistent P5-P12 renderer using stable section slots, hashed lookup, local light lists,
 * GPU-resident temporal history, edge-aware spatial denoising and a one-bounce diffuse GI baseline.
 */
public final class P5StableLookupRenderer {
    private static final int MAX_SECTIONS = 64;
    private static final int MIN_SECTIONS = 16;
    private static final int HEADER_WORDS = 96;
    private static final int LOOKUP_CAPACITY = 128;
    private static final int LOOKUP_BASE_WORD = HEADER_WORDS;
    private static final int LOOKUP_WORDS = LOOKUP_CAPACITY * GpuSectionLookupTable.WORDS_PER_BUCKET;
    private static final int VOXEL_BASE_WORD = LOOKUP_BASE_WORD + LOOKUP_WORDS;
    private static final int SECTION_LIGHT_COUNT_BASE_WORD = VOXEL_BASE_WORD + MAX_SECTIONS * SectionVoxelData.VOXEL_COUNT;
    private static final int SECTION_LIGHT_INDEX_BASE_WORD = SECTION_LIGHT_COUNT_BASE_WORD + MAX_SECTIONS;
    private static final int LIGHT_DATA_BASE_WORD = SECTION_LIGHT_INDEX_BASE_WORD
            + MAX_SECTIONS * GpuSectionLightLists.MAX_LIGHTS_PER_SECTION;
    private static final int LIGHT_WORDS_PER_RECORD = 8;
    private static final int MAX_MATERIALS = 4096;
    private static final int MATERIAL_EMISSION_WORDS_PER_RECORD = 16;
    private static final int MATERIAL_EMISSION_BASE_WORD = LIGHT_DATA_BASE_WORD
            + GpuSectionLightLists.MAX_GLOBAL_LIGHTS * LIGHT_WORDS_PER_RECORD;
    private static final int STATIC_DATA_END_WORD = MATERIAL_EMISSION_BASE_WORD
            + MAX_MATERIALS * MATERIAL_EMISSION_WORDS_PER_RECORD;
    private static final int HISTORY_RECORD_WORDS = 8;
    private static final int MAX_STEPS = 512;
    private static final float MAX_DISTANCE = 256.0f;
    private static final int CAMERA_UPLOAD_WORDS = HEADER_WORDS;
    private static final int SOFT_SHADOW_SAMPLES = 4;
    private static final float SOFT_SHADOW_ANGULAR_RADIUS = 0.055f;
    private static final float TEMPORAL_HISTORY_WEIGHT = 0.80f;
    private static final int SPATIAL_DENOISE_RADIUS = 1;
    private static final float GI_MAX_DISTANCE = 48.0f;
    private static final float GI_STRENGTH = 0.65f;
    private static final int GI_HISTORY_MAX_SAMPLES = 64;

    private static final GpuSectionSlotAllocator SLOT_ALLOCATOR = new GpuSectionSlotAllocator(MAX_SECTIONS);
    private static final Set<SectionKey> RESIDENT_KEYS = new HashSet<>();

    private static final String SHADER = """
            #version 450
            layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
            layout(set = 0, binding = 0, std430) buffer SceneBuffer {
                uint data[];
            } scene;

            const uint LOOKUP_BASE = 96u;
            const uint LOOKUP_CAPACITY = 128u;
            const uint LOOKUP_MASK = 127u;
            const uint LOOKUP_WORDS_PER_BUCKET = 4u;
            const uint VOXEL_BASE = 608u;
            const uint VOXELS_PER_SECTION = 4096u;
            const uint SECTION_LIGHT_COUNT_BASE = 262752u;
            const uint SECTION_LIGHT_INDEX_BASE = 262816u;
            const uint LIGHT_DATA_BASE = 263328u;
            const uint MAX_LIGHTS_PER_SECTION = 8u;
            const uint LIGHT_WORDS_PER_RECORD = 8u;
            const uint MATERIAL_EMISSION_BASE = 265376u;
            const uint MATERIAL_EMISSION_WORDS_PER_RECORD = 16u;
            const uint HISTORY_RECORD_WORDS = 8u;
            const float TEMPORAL_HISTORY_WEIGHT = 0.80;
            const uint GI_HISTORY_MAX_SAMPLES = 64u;
            const uint HISTORY_MATERIAL_MASK = 0x0000FFFFu;
            const float GI_MAX_DISTANCE = 48.0;
            const float GI_STRENGTH = 0.65;
            const vec3 DEBUG_LIGHT_DIRECTION = normalize(vec3(0.45, 0.85, 0.30));
            const float SOFT_SHADOW_ANGULAR_RADIUS = 0.055;
            const vec2 SOFT_SHADOW_OFFSETS[4] = vec2[4](
                vec2(0.32, 0.08),
                vec2(-0.26, 0.31),
                vec2(0.14, -0.36),
                vec2(-0.30, -0.19)
            );

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
                    if (all(equal(candidate, sectionCoord))) {
                        return int(slotPlusOne - 1u);
                    }
                }
                return -1;
            }

            int sectionSlotForVoxel(ivec3 voxel) {
                return findSectionSlot(ivec3(
                    floorDiv16(voxel.x),
                    floorDiv16(voxel.y),
                    floorDiv16(voxel.z)
                ));
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
                uint voxelBase = VOXEL_BASE + uint(slot) * VOXELS_PER_SECTION;
                return scene.data[voxelBase + index];
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

            HitResult traceRay(vec3 origin, vec3 direction) {
                return traceRayLimited(origin, direction, uintBitsToFloat(scene.data[7]));
            }

            uint packRgba(vec3 rgb, uint alpha) {
                uvec3 c = uvec3(clamp(rgb, vec3(0.0), vec3(1.0)) * 255.0 + 0.5);
                return c.r | (c.g << 8u) | (c.b << 16u) | ((alpha & 255u) << 24u);
            }

            vec3 unpackRgb(uint rgba) {
                return vec3(
                    float(rgba & 255u),
                    float((rgba >> 8u) & 255u),
                    float((rgba >> 16u) & 255u)
                ) / 255.0;
            }

            vec3 materialColor(uint materialId) {
                uint h = materialId * 1664525u + 1013904223u;
                return vec3(
                    float((h >> 0u) & 255u),
                    float((h >> 8u) & 255u),
                    float((h >> 16u) & 255u)
                ) / 255.0;
            }

            vec4 materialEmission(uint materialId) {
                uint materialCount = scene.data[23];
                if (materialId >= materialCount) return vec4(0.0);
                uint base = MATERIAL_EMISSION_BASE + materialId * MATERIAL_EMISSION_WORDS_PER_RECORD;
                return vec4(
                    uintBitsToFloat(scene.data[base]),
                    uintBitsToFloat(scene.data[base + 1u]),
                    uintBitsToFloat(scene.data[base + 2u]),
                    uintBitsToFloat(scene.data[base + 3u])
                );
            }

            vec3 resolvedSurfaceNormal(HitResult hit, vec3 primaryDirection) {
                vec3 normal = vec3(hit.normal);
                if (length(normal) < 0.5) {
                    return normalize(-primaryDirection);
                }
                return normalize(normal);
            }

            uint hashBits(uint value) {
                value ^= value >> 16u;
                value *= 0x7FEB352Du;
                value ^= value >> 15u;
                value *= 0x846CA68Bu;
                value ^= value >> 16u;
                return value;
            }

            float random01(inout uint state) {
                state = hashBits(state);
                return float(state & 0x00FFFFFFu) / 16777216.0;
            }

            vec3 cosineHemisphereDirection(vec3 normal, uvec2 pixel, uint sampleIndex) {
                uint state = hashBits(
                    pixel.x * 0x9E3779B1u
                    ^ pixel.y * 0x85EBCA77u
                    ^ sampleIndex * 0xC2B2AE3Du
                );
                float u1 = random01(state);
                float u2 = random01(state);
                float radius = sqrt(max(u1, 0.0));
                float phi = 6.28318530718 * u2;
                vec3 localDirection = vec3(
                    radius * cos(phi),
                    radius * sin(phi),
                    sqrt(max(0.0, 1.0 - u1))
                );

                vec3 helper = abs(normal.y) < 0.95 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
                vec3 tangent = normalize(cross(helper, normal));
                vec3 bitangent = normalize(cross(normal, tangent));
                return normalize(
                    tangent * localDirection.x
                    + bitangent * localDirection.y
                    + normal * localDirection.z
                );
            }

            vec3 directionalSampleDirection(uint sampleIndex) {
                vec3 helper = abs(DEBUG_LIGHT_DIRECTION.y) < 0.95
                        ? vec3(0.0, 1.0, 0.0)
                        : vec3(1.0, 0.0, 0.0);
                vec3 tangent = normalize(cross(helper, DEBUG_LIGHT_DIRECTION));
                vec3 bitangent = normalize(cross(DEBUG_LIGHT_DIRECTION, tangent));
                vec2 offset = SOFT_SHADOW_OFFSETS[int(sampleIndex & 3u)];
                return normalize(
                    DEBUG_LIGHT_DIRECTION
                    + tangent * (offset.x * SOFT_SHADOW_ANGULAR_RADIUS)
                    + bitangent * (offset.y * SOFT_SHADOW_ANGULAR_RADIUS)
                );
            }

            uint hardShadowColor(HitResult hit, vec3 primaryOrigin, vec3 primaryDirection) {
                vec3 surfaceNormal = resolvedSurfaceNormal(hit, primaryDirection);
                float nDotL = max(dot(surfaceNormal, DEBUG_LIGHT_DIRECTION), 0.0);
                float visibility = 0.0;
                if (nDotL > 0.0) {
                    vec3 hitPoint = primaryOrigin + primaryDirection * hit.distance;
                    vec3 shadowOrigin = hitPoint + surfaceNormal * 0.02 + DEBUG_LIGHT_DIRECTION * 0.01;
                    HitResult blocker = traceRay(shadowOrigin, DEBUG_LIGHT_DIRECTION);
                    visibility = blocker.hit == 0u ? 1.0 : 0.0;
                }

                float lighting = 0.12 + 0.88 * nDotL * visibility;
                return packRgba(materialColor(hit.materialId) * lighting, 255u);
            }

            float softDirectionalVisibility(vec3 hitPoint, vec3 surfaceNormal) {
                float visibleSamples = 0.0;
                float validSamples = 0.0;
                uint shadowSamples = clamp(scene.data[45], 1u, 4u);

                for (uint sampleIndex = 0u; sampleIndex < 4u; sampleIndex++) {
                    if (sampleIndex >= shadowSamples) break;
                    vec3 sampleDirection = directionalSampleDirection(sampleIndex);
                    if (dot(surfaceNormal, sampleDirection) <= 0.0) continue;

                    validSamples += 1.0;
                    vec3 shadowOrigin = hitPoint + surfaceNormal * 0.025 + sampleDirection * 0.01;
                    HitResult blocker = traceRay(shadowOrigin, sampleDirection);
                    if (blocker.hit == 0u) visibleSamples += 1.0;
                }

                return validSamples > 0.0 ? visibleSamples / validSamples : 0.0;
            }

            uint softShadowColor(HitResult hit, vec3 primaryOrigin, vec3 primaryDirection) {
                vec3 surfaceNormal = resolvedSurfaceNormal(hit, primaryDirection);
                float nDotL = max(dot(surfaceNormal, DEBUG_LIGHT_DIRECTION), 0.0);
                float visibility = 0.0;
                if (nDotL > 0.0) {
                    vec3 hitPoint = primaryOrigin + primaryDirection * hit.distance;
                    visibility = softDirectionalVisibility(hitPoint, surfaceNormal);
                }

                vec4 surfaceEmission = materialEmission(hit.materialId);
                vec3 emitted = surfaceEmission.rgb * surfaceEmission.a * uintBitsToFloat(scene.data[83]);
                float lighting = 0.12 + 0.88 * nDotL * visibility;
                return packRgba(materialColor(hit.materialId) * lighting + emitted, 255u);
            }

            uint temporalCurrentColor(HitResult hit, vec3 primaryOrigin, vec3 primaryDirection, uint sampleIndex) {
                vec3 surfaceNormal = resolvedSurfaceNormal(hit, primaryDirection);
                vec3 sampleDirection = directionalSampleDirection(sampleIndex);
                float nDotL = max(dot(surfaceNormal, sampleDirection), 0.0);
                float visibility = 0.0;
                if (nDotL > 0.0) {
                    vec3 hitPoint = primaryOrigin + primaryDirection * hit.distance;
                    vec3 shadowOrigin = hitPoint + surfaceNormal * 0.025 + sampleDirection * 0.01;
                    HitResult blocker = traceRay(shadowOrigin, sampleDirection);
                    visibility = blocker.hit == 0u ? 1.0 : 0.0;
                }

                vec4 surfaceEmission = materialEmission(hit.materialId);
                vec3 emitted = surfaceEmission.rgb * surfaceEmission.a * uintBitsToFloat(scene.data[83]);
                float lighting = 0.12 + 0.88 * nDotL * visibility;
                return packRgba(materialColor(hit.materialId) * lighting + emitted, 255u);
            }

            vec3 secondarySurfaceRadiance(HitResult hit, vec3 rayOrigin, vec3 rayDirection) {
                vec3 surfaceNormal = resolvedSurfaceNormal(hit, rayDirection);
                float nDotL = max(dot(surfaceNormal, DEBUG_LIGHT_DIRECTION), 0.0);
                float visibility = 0.0;
                if (nDotL > 0.0) {
                    vec3 hitPoint = rayOrigin + rayDirection * hit.distance;
                    vec3 shadowOrigin = hitPoint + surfaceNormal * 0.025 + DEBUG_LIGHT_DIRECTION * 0.01;
                    HitResult blocker = traceRay(shadowOrigin, DEBUG_LIGHT_DIRECTION);
                    visibility = blocker.hit == 0u ? 1.0 : 0.0;
                }

                vec4 emission = materialEmission(hit.materialId);
                vec3 emitted = emission.rgb * emission.a * uintBitsToFloat(scene.data[83]);
                float lighting = 0.04 + 0.96 * nDotL * visibility;
                return materialColor(hit.materialId) * lighting + emitted;
            }

            vec3 oneBounceIndirectRgb(
                    HitResult primaryHit,
                    vec3 primaryOrigin,
                    vec3 primaryDirection,
                    uvec2 pixel,
                    uint sampleIndex
            ) {
                vec3 primaryNormal = resolvedSurfaceNormal(primaryHit, primaryDirection);
                vec3 primaryPoint = primaryOrigin + primaryDirection * primaryHit.distance;
                vec3 bounceDirection = cosineHemisphereDirection(primaryNormal, pixel, sampleIndex);
                vec3 bounceOrigin = primaryPoint + primaryNormal * 0.035 + bounceDirection * 0.01;
                HitResult bounceHit = traceRayLimited(bounceOrigin, bounceDirection, GI_MAX_DISTANCE);
                if (bounceHit.hit == 0u) return vec3(0.0);

                vec3 incomingRadiance = secondarySurfaceRadiance(bounceHit, bounceOrigin, bounceDirection);
                return materialColor(primaryHit.materialId) * incomingRadiance * GI_STRENGTH;
            }

            vec3 tlLocalEmitterSample(
                    vec3 lightCenter,
                    vec3 receiverPoint,
                    uint sampleIndex,
                    out vec3 emitterNormal
            ) {
                vec3 fromLight = receiverPoint - lightCenter;
                vec3 axisMagnitude = abs(fromLight);
                vec3 axisU;
                vec3 axisV;

                if (axisMagnitude.x >= axisMagnitude.y && axisMagnitude.x >= axisMagnitude.z) {
                    emitterNormal = vec3(fromLight.x >= 0.0 ? 1.0 : -1.0, 0.0, 0.0);
                    axisU = vec3(0.0, 1.0, 0.0);
                    axisV = vec3(0.0, 0.0, 1.0);
                } else if (axisMagnitude.y >= axisMagnitude.z) {
                    emitterNormal = vec3(0.0, fromLight.y >= 0.0 ? 1.0 : -1.0, 0.0);
                    axisU = vec3(1.0, 0.0, 0.0);
                    axisV = vec3(0.0, 0.0, 1.0);
                } else {
                    emitterNormal = vec3(0.0, 0.0, fromLight.z >= 0.0 ? 1.0 : -1.0);
                    axisU = vec3(1.0, 0.0, 0.0);
                    axisV = vec3(0.0, 1.0, 0.0);
                }

                const vec2 LOCAL_AREA_OFFSETS[4] = vec2[4](
                    vec2(0.00, 0.00),
                    vec2(0.28, 0.20),
                    vec2(-0.26, 0.22),
                    vec2(0.04, -0.30)
                );
                vec2 offset = LOCAL_AREA_OFFSETS[int(sampleIndex & 3u)];
                return lightCenter
                        + emitterNormal * 0.495
                        + axisU * offset.x
                        + axisV * offset.y;
            }

            uint localLightColor(HitResult hit, vec3 primaryOrigin, vec3 primaryDirection, bool emissiveMode) {
                int slot = sectionSlotForVoxel(hit.voxel);
                vec4 surfaceEmission = emissiveMode ? materialEmission(hit.materialId) : vec4(0.0);
                vec3 emitted = surfaceEmission.rgb * surfaceEmission.a * uintBitsToFloat(scene.data[83]);
                if (slot < 0) {
                    return packRgba(materialColor(hit.materialId) * uintBitsToFloat(scene.data[82]) + emitted, 255u);
                }

                vec3 surfaceNormal = resolvedSurfaceNormal(hit, primaryDirection);
                vec3 hitPoint = primaryOrigin + primaryDirection * hit.distance;
                vec3 lighting = vec3(uintBitsToFloat(scene.data[82]));
                uint lightCount = min(scene.data[SECTION_LIGHT_COUNT_BASE + uint(slot)], MAX_LIGHTS_PER_SECTION);
                uint areaSamples = clamp(scene.data[45], 1u, 4u);

                for (uint localIndex = 0u; localIndex < lightCount; localIndex++) {
                    uint listWord = SECTION_LIGHT_INDEX_BASE + uint(slot) * MAX_LIGHTS_PER_SECTION + localIndex;
                    uint lightIndex = scene.data[listWord];
                    uint lightBase = LIGHT_DATA_BASE + lightIndex * LIGHT_WORDS_PER_RECORD;
                    vec3 lightPosition = vec3(
                        uintBitsToFloat(scene.data[lightBase]),
                        uintBitsToFloat(scene.data[lightBase + 1u]),
                        uintBitsToFloat(scene.data[lightBase + 2u])
                    );
                    float radius = uintBitsToFloat(scene.data[lightBase + 3u]);
                    vec3 lightColor = emissiveMode ? vec3(
                        uintBitsToFloat(scene.data[lightBase + 4u]),
                        uintBitsToFloat(scene.data[lightBase + 5u]),
                        uintBitsToFloat(scene.data[lightBase + 6u])
                    ) : vec3(1.0, 0.82, 0.58);
                    float encodedIntensity = uintBitsToFloat(scene.data[lightBase + 7u]);
                    bool pointEmitter = encodedIntensity < 0.0;
                    float intensity = emissiveMode
                            ? abs(encodedIntensity)
                            : clamp((radius - 0.5) / 15.0, 0.0, 1.0);

                    uint lightSamples = pointEmitter ? 1u : areaSamples;
                    float sampleLighting = 0.0;
                    for (uint areaIndex = 0u; areaIndex < 4u; areaIndex++) {
                        if (areaIndex >= lightSamples) break;

                        vec3 emitterNormal;
                        vec3 samplePosition;
                        if (pointEmitter) {
                            samplePosition = lightPosition;
                            emitterNormal = vec3(0.0);
                        } else {
                            samplePosition = tlLocalEmitterSample(
                                lightPosition,
                                hitPoint,
                                areaIndex,
                                emitterNormal
                            );
                        }
                        vec3 toLight = samplePosition - hitPoint;
                        float distanceToLight = length(toLight);
                        if (distanceToLight <= 0.0001 || distanceToLight >= radius) continue;

                        vec3 lightDirection = toLight / distanceToLight;
                        float nDotL = max(dot(surfaceNormal, lightDirection), 0.0);
                        if (nDotL <= 0.0) continue;

                        float emitterCosine = pointEmitter
                                ? 1.0
                                : max(dot(emitterNormal, -lightDirection), 0.0);
                        if (emitterCosine <= 0.0) continue;

                        float visibility = 1.0;
                        float shadowMaxDistance = max(distanceToLight - 0.06, 0.0);
                        if (shadowMaxDistance > 0.02) {
                            vec3 shadowOrigin = hitPoint + surfaceNormal * 0.025 + lightDirection * 0.01;
                            HitResult blocker = traceRayLimited(shadowOrigin, lightDirection, shadowMaxDistance);
                            visibility = blocker.hit == 0u ? 1.0 : 0.0;
                        }

                        float range = clamp(1.0 - distanceToLight / radius, 0.0, 1.0);
                        float attenuation = range * range;
                        sampleLighting += nDotL * emitterCosine * attenuation * visibility;
                    }

                    lighting += lightColor * (
                        uintBitsToFloat(scene.data[54]) * intensity * sampleLighting / float(lightSamples)
                    );
                }

                return packRgba(materialColor(hit.materialId) * lighting + emitted, 255u);
            }

            uint giCurrentColor(
                    HitResult hit,
                    vec3 primaryOrigin,
                    vec3 primaryDirection,
                    uvec2 pixel,
                    uint sampleIndex,
                    bool indirectOnly
            ) {
                vec3 indirect = oneBounceIndirectRgb(hit, primaryOrigin, primaryDirection, pixel, sampleIndex);
                if (indirectOnly) {
                    return packRgba(indirect * uintBitsToFloat(scene.data[84]), 255u);
                }

                vec3 direct = unpackRgb(temporalCurrentColor(hit, primaryOrigin, primaryDirection, sampleIndex));
                vec3 localWithBase = unpackRgb(localLightColor(hit, primaryOrigin, primaryDirection, true));
                vec4 emission = materialEmission(hit.materialId);
                vec3 emitted = emission.rgb * emission.a * uintBitsToFloat(scene.data[83]);
                vec3 localBase = materialColor(hit.materialId) * uintBitsToFloat(scene.data[82]) + emitted;
                vec3 localContribution = max(localWithBase - localBase, vec3(0.0));
                return packRgba(direct + localContribution + indirect, 255u);
            }

            bool reprojectToPrevious(vec3 worldPoint, uint width, uint height, out uvec2 previousPixel) {
                if (scene.data[26] == 0u) return false;

                vec3 previousOrigin = vec3(
                    uintBitsToFloat(scene.data[27]),
                    uintBitsToFloat(scene.data[28]),
                    uintBitsToFloat(scene.data[29])
                );
                vec3 previousRight = vec3(
                    uintBitsToFloat(scene.data[30]),
                    uintBitsToFloat(scene.data[31]),
                    uintBitsToFloat(scene.data[32])
                );
                vec3 previousUp = vec3(
                    uintBitsToFloat(scene.data[33]),
                    uintBitsToFloat(scene.data[34]),
                    uintBitsToFloat(scene.data[35])
                );
                vec3 previousForward = vec3(
                    uintBitsToFloat(scene.data[36]),
                    uintBitsToFloat(scene.data[37]),
                    uintBitsToFloat(scene.data[38])
                );
                float previousTanHalfFov = uintBitsToFloat(scene.data[39]);
                float previousAspect = uintBitsToFloat(scene.data[40]);

                vec3 relative = worldPoint - previousOrigin;
                float depth = dot(relative, previousForward);
                if (depth <= 0.001 || previousTanHalfFov <= 0.000001 || previousAspect <= 0.000001) return false;

                float ndcX = dot(relative, previousRight) / (depth * previousAspect * previousTanHalfFov);
                float ndcY = dot(relative, previousUp) / (depth * previousTanHalfFov);
                if (abs(ndcX) > 1.0 || abs(ndcY) > 1.0) return false;

                vec2 previousPosition = vec2(
                    (ndcX * 0.5 + 0.5) * float(width) - 0.5,
                    (0.5 - ndcY * 0.5) * float(height) - 0.5
                );
                ivec2 rounded = ivec2(floor(previousPosition + vec2(0.5)));
                if (rounded.x < 0 || rounded.y < 0 || rounded.x >= int(width) || rounded.y >= int(height)) {
                    return false;
                }
                previousPixel = uvec2(rounded);
                return true;
            }

            uint historyMaterial(uint historyBase) {
                return scene.data[historyBase + 7u] & HISTORY_MATERIAL_MASK;
            }

            uint historySampleCount(uint historyBase) {
                return scene.data[historyBase + 7u] >> 16u;
            }

            bool historyMatches(uint historyBase, HitResult hit) {
                if (historyMaterial(historyBase) != hit.materialId + 1u) return false;
                ivec3 previousVoxel = ivec3(
                    int(scene.data[historyBase + 1u]),
                    int(scene.data[historyBase + 2u]),
                    int(scene.data[historyBase + 3u])
                );
                ivec3 previousNormal = ivec3(
                    int(scene.data[historyBase + 4u]),
                    int(scene.data[historyBase + 5u]),
                    int(scene.data[historyBase + 6u])
                );
                return all(equal(previousVoxel, hit.voxel)) && all(equal(previousNormal, hit.normal));
            }

            vec3 spatialHistoryRgb(
                    uvec2 centerPixel,
                    uint width,
                    uint height,
                    HitResult hit,
                    vec3 centerRgb
            ) {
                vec3 weightedRgb = vec3(0.0);
                float totalWeight = 0.0;
                ivec2 center = ivec2(centerPixel);

                int denoiseRadius = int(min(scene.data[50], 2u));
                for (int offsetY = -2; offsetY <= 2; offsetY++) {
                    for (int offsetX = -2; offsetX <= 2; offsetX++) {
                        if (abs(offsetX) > denoiseRadius || abs(offsetY) > denoiseRadius) continue;
                        ivec2 samplePixel = center + ivec2(offsetX, offsetY);
                        if (samplePixel.x < 0 || samplePixel.y < 0
                                || samplePixel.x >= int(width) || samplePixel.y >= int(height)) {
                            continue;
                        }

                        uint sampleLinear = uint(samplePixel.y) * width + uint(samplePixel.x);
                        uint sampleBase = scene.data[24] + sampleLinear * HISTORY_RECORD_WORDS;
                        if (historyMaterial(sampleBase) != hit.materialId + 1u) continue;

                        ivec3 sampleNormal = ivec3(
                            int(scene.data[sampleBase + 4u]),
                            int(scene.data[sampleBase + 5u]),
                            int(scene.data[sampleBase + 6u])
                        );
                        if (!all(equal(sampleNormal, hit.normal))) continue;

                        ivec3 sampleVoxel = ivec3(
                            int(scene.data[sampleBase + 1u]),
                            int(scene.data[sampleBase + 2u]),
                            int(scene.data[sampleBase + 3u])
                        );
                        ivec3 voxelDelta = abs(sampleVoxel - hit.voxel);
                        if (voxelDelta.x > 1 || voxelDelta.y > 1 || voxelDelta.z > 1) continue;

                        float spatialWeight;
                        if (offsetX == 0 && offsetY == 0) {
                            spatialWeight = 1.0;
                        } else if (offsetX == 0 || offsetY == 0) {
                            spatialWeight = 0.65;
                        } else {
                            spatialWeight = 0.45;
                        }
                        float voxelWeight = 1.0 / (1.0 + 0.35 * float(voxelDelta.x + voxelDelta.y + voxelDelta.z));
                        float weight = spatialWeight * voxelWeight;
                        weightedRgb += unpackRgb(scene.data[sampleBase]) * weight;
                        totalWeight += weight;
                    }
                }

                return totalWeight > 0.0001 ? weightedRgb / totalWeight : centerRgb;
            }

            uint currentTemporalSampleColor(
                    HitResult hit,
                    vec3 primaryOrigin,
                    vec3 primaryDirection,
                    uvec2 pixel
            ) {
                uint mode = scene.data[22];
                uint sampleIndex = scene.data[41];
                if (mode == 10u) {
                    return giCurrentColor(hit, primaryOrigin, primaryDirection, pixel, sampleIndex, true);
                }
                if (mode == 11u) {
                    return giCurrentColor(hit, primaryOrigin, primaryDirection, pixel, sampleIndex, false);
                }
                return temporalCurrentColor(hit, primaryOrigin, primaryDirection, sampleIndex);
            }

            uint temporalHistoryColor(
                    HitResult hit,
                    vec3 primaryOrigin,
                    vec3 primaryDirection,
                    uvec2 pixel,
                    uint width,
                    uint height,
                    out uint outputSampleCount
            ) {
                uint currentColor = currentTemporalSampleColor(hit, primaryOrigin, primaryDirection, pixel);
                outputSampleCount = 1u;
                if (scene.data[26] == 0u) return currentColor;

                vec3 hitPoint = primaryOrigin + primaryDirection * hit.distance;
                uvec2 previousPixel;
                if (!reprojectToPrevious(hitPoint, width, height, previousPixel)) return currentColor;

                uint previousLinear = previousPixel.y * width + previousPixel.x;
                uint historyBase = scene.data[24] + previousLinear * HISTORY_RECORD_WORDS;
                if (!historyMatches(historyBase, hit)) return currentColor;

                vec3 currentRgb = unpackRgb(currentColor);
                vec3 historyRgb = unpackRgb(scene.data[historyBase]);
                uint mode = scene.data[22];
                if (mode == 9u || mode == 10u || mode == 11u) {
                    historyRgb = spatialHistoryRgb(previousPixel, width, height, hit, historyRgb);
                }
                if (mode == 10u || mode == 11u) {
                    uint previousSamples = clamp(historySampleCount(historyBase), 1u, GI_HISTORY_MAX_SAMPLES);
                    outputSampleCount = min(previousSamples + 1u, GI_HISTORY_MAX_SAMPLES);
                    float historyWeight = float(previousSamples) / float(previousSamples + 1u);
                    return packRgba(mix(currentRgb, historyRgb, historyWeight), 255u);
                }
                float temporalWeight = clamp(uintBitsToFloat(scene.data[49]), 0.0, 0.95);
                return packRgba(mix(currentRgb, historyRgb, temporalWeight), 255u);
            }

            void writeHistory(uvec2 pixel, uint width, HitResult hit, uint color, uint sampleCount) {
                uint linear = pixel.y * width + pixel.x;
                uint historyBase = scene.data[25] + linear * HISTORY_RECORD_WORDS;
                scene.data[historyBase] = color;
                if (hit.hit == 0u) {
                    scene.data[historyBase + 7u] = 0u;
                    return;
                }
                scene.data[historyBase + 1u] = uint(hit.voxel.x);
                scene.data[historyBase + 2u] = uint(hit.voxel.y);
                scene.data[historyBase + 3u] = uint(hit.voxel.z);
                scene.data[historyBase + 4u] = uint(hit.normal.x);
                scene.data[historyBase + 5u] = uint(hit.normal.y);
                scene.data[historyBase + 6u] = uint(hit.normal.z);
                uint packedSamples = min(sampleCount, GI_HISTORY_MAX_SAMPLES) << 16u;
                scene.data[historyBase + 7u] = packedSamples | ((hit.materialId + 1u) & HISTORY_MATERIAL_MASK);
            }

            uint debugColor(HitResult hit, vec3 primaryOrigin, vec3 primaryDirection) {
                if (hit.hit == 0u) return packRgba(vec3(0.03, 0.05, 0.08), 255u);

                uint mode = scene.data[22];
                if (mode == 0u) {
                    return packRgba(vec3(hit.normal) * 0.5 + 0.5, 255u);
                }
                if (mode == 1u) {
                    return packRgba(materialColor(hit.materialId), 255u);
                }
                if (mode == 2u) {
                    float maxDistance = uintBitsToFloat(scene.data[7]);
                    float v = 1.0 - clamp(hit.distance / maxDistance, 0.0, 1.0);
                    return packRgba(vec3(v), 255u);
                }
                if (mode == 3u) {
                    float t = clamp(float(hit.steps) / max(float(scene.data[6]), 1.0), 0.0, 1.0);
                    return packRgba(vec3(t, t * t, 1.0 - t), 255u);
                }
                if (mode == 4u) {
                    return hardShadowColor(hit, primaryOrigin, primaryDirection);
                }
                if (mode == 5u) {
                    return localLightColor(hit, primaryOrigin, primaryDirection, false);
                }
                if (mode == 6u) {
                    return localLightColor(hit, primaryOrigin, primaryDirection, true);
                }
                return softShadowColor(hit, primaryOrigin, primaryDirection);
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

                HitResult primaryHit = traceRay(origin, direction);
                uint mode = scene.data[22];
                uint color;
                if (mode == 8u || mode == 9u || mode == 10u || mode == 11u) {
                    uint historySamples = 1u;
                    if (primaryHit.hit == 0u) {
                        color = packRgba(vec3(0.03, 0.05, 0.08), 255u);
                    } else {
                        color = temporalHistoryColor(primaryHit, origin, direction, pixel, width, height, historySamples);
                    }
                    writeHistory(pixel, width, primaryHit, color, historySamples);
                } else {
                    color = debugColor(primaryHit, origin, direction);
                }

                uint pixelBase = scene.data[3];
                scene.data[pixelBase + pixel.y * width + pixel.x] = color;
            }
            """;

    public enum DebugMode {
        NORMAL(0, "Normal"),
        MATERIAL(1, "Material"),
        DISTANCE(2, "Distance"),
        STEPS(3, "Steps"),
        HARD_SHADOW(4, "Hard Shadow"),
        SOFT_SHADOW(7, "Soft Shadow"),
        TEMPORAL_HISTORY(8, "Temporal History"),
        SPATIAL_DENOISE(9, "Spatial Denoise"),
        INDIRECT_GI(10, "Indirect GI"),
        GI_COMPOSITE(11, "GI Composite"),
        FLUID_GEOMETRY(12, "P14E Fluid Geometry"),
        LOCAL_LIGHTS(5, "Local Lights"),
        EMISSIVE_MATERIALS(6, "Emissive Materials");

        private final int shaderValue;
        private final String label;

        DebugMode(int shaderValue, String label) {
            this.shaderValue = shaderValue;
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final DebugMode[] PERFORMANCE_PROBE_MODES = {
            DebugMode.NORMAL,
            DebugMode.HARD_SHADOW,
            DebugMode.LOCAL_LIGHTS,
            DebugMode.INDIRECT_GI,
            DebugMode.GI_COMPOSITE
    };
    private static final int PERFORMANCE_PROBE_WARMUP_FRAMES = 10;
    private static final int PERFORMANCE_PROBE_SAMPLE_FRAMES = 30;

    private static Resources resources;
    private static DebugMode mode = DebugMode.GI_COMPOSITE;
    private static boolean inFlight;
    private static boolean ready;
    private static boolean resetRequested;
    private static boolean firstFrameLogged;
    private static boolean p7Logged;
    private static boolean p8Logged;
    private static boolean p9Logged;
    private static boolean p10Logged;
    private static boolean p11Logged;
    private static boolean p12Logged;
    private static boolean sceneUploadRequired = true;
    private static boolean historyValid;
    private static long sceneSignature = Long.MIN_VALUE;
    private static long lastSubmittedFrame = -1;
    private static int historyReadIndex = -1;
    private static int lastLookupMaxProbe;
    private static int lastLightCount;
    private static int lastMaxLightsPerSection;
    private static int lastPopulatedLightLists;
    private static int lastEmissiveMaterialCount;
    private static FrameSnapshot lastCompletedFrame;
    private static DebugMode lastCompletedMode;
    private static String dimensionId;
    private static long lastSettingsRevision = Long.MIN_VALUE;
    private static long lastRuntimeTuningRevision = Long.MIN_VALUE;
    private static long lastAnimationSignature = Long.MIN_VALUE;
    private static int activeRenderWidth;
    private static int activeRenderHeight;
    private static DebugMode performanceProbeMode;
    private static int performanceProbeWarmup;
    private static int performanceProbeSamples;
    private static long performanceProbeAccumulatedNanos;

    private P5StableLookupRenderer() {
    }

    private static boolean usesTemporalHistory(DebugMode debugMode) {
        if (RendererSettings.temporalQuality() == RendererSettings.TemporalQuality.OFF) return false;
        return debugMode == DebugMode.TEMPORAL_HISTORY
                || debugMode == DebugMode.SPATIAL_DENOISE
                || debugMode == DebugMode.INDIRECT_GI
                || debugMode == DebugMode.GI_COMPOSITE;
    }

    private static boolean historyCompatible(DebugMode current, DebugMode previous) {
        if (current == null || previous == null) return false;
        boolean currentDirectTemporal = current == DebugMode.TEMPORAL_HISTORY || current == DebugMode.SPATIAL_DENOISE;
        boolean previousDirectTemporal = previous == DebugMode.TEMPORAL_HISTORY || previous == DebugMode.SPATIAL_DENOISE;
        if (currentDirectTemporal && previousDirectTemporal) return true;
        return current == previous && (current == DebugMode.INDIRECT_GI || current == DebugMode.GI_COMPOSITE);
    }

    public static void runOnRenderThread() {
        if (!RendererSettings.rendererEnabled()) {
            historyValid = false;
            return;
        }
        if (!P5WorldDebugComposite.ready() || inFlight || resetRequested) return;

        FrameSnapshot frame = SceneExtractionBridge.latestFrame();
        if (frame == null || !frame.dimensionId().equals(SceneExtractionBridge.scene().activeDimension())) return;
        if (frame.frameIndex() == lastSubmittedFrame) return;
        if (SceneExtractionBridge.scene().populatedSectionCount() < MIN_SECTIONS) return;

        VulkanCapabilities capabilities = dev.totem.lumen.render.RendererBootstrap.vulkanCapabilities();
        VulkanDevice device = MinecraftVulkanBridge.currentDevice();
        if (capabilities == null || device == null || !capabilities.canUseMinecraftFrameSubmissionForCompute()) return;

        List<SectionSnapshot> sections = nearestSections(frame);
        if (sections.size() < MIN_SECTIONS) return;

        long settingsRevision = RendererSettings.revision();
        boolean settingsChanged = settingsRevision != lastSettingsRevision;
        if (settingsChanged) {
            historyValid = false;
            lastSettingsRevision = settingsRevision;
        }

        RendererRuntimeTuningRegistry.ensureLoaded(Minecraft.getInstance().getResourceManager());
        long runtimeTuningRevision = RendererRuntimeTuningRegistry.revision();
        if (runtimeTuningRevision != lastRuntimeTuningRevision) {
            historyValid = false;
            lastRuntimeTuningRevision = runtimeTuningRevision;
        }

        long animationTick = currentAnimationTick(frame);
        long animationSignature = LabPbrTextureRegistry.loadedAnimatedCount() == 0
                ? Long.MIN_VALUE
                : LabPbrTextureRegistry.animationSignature(animationTick);
        if (animationSignature != lastAnimationSignature) {
            historyValid = false;
            lastAnimationSignature = animationSignature;
        }

        int windowWidth = Math.max(1, Minecraft.getInstance().getWindow().getWidth());
        int windowHeight = Math.max(1, Minecraft.getInstance().getWindow().getHeight());
        int targetWidth = RendererSettings.internalResolution().targetWidth(windowWidth);
        int targetHeight = Math.max(1, Math.round(targetWidth * (windowHeight / (float) windowWidth)));
        int capacityWidth = RendererSettings.InternalResolution.HIGH.targetWidth(windowWidth);
        int capacityHeight = Math.max(1, Math.round(capacityWidth * (windowHeight / (float) windowWidth)));

        if (settingsChanged) {
            TotemLumenClient.LOGGER.info(
                    "Renderer settings ACTIVE: window={}x{}, render={}x{}, internalResolution={}, giSamples={}, shadowSamples={}, rayDistance={}, reflections={}, reflectionBounces={}, reflectionDistance={}, temporal={}, denoiseRadius={}",
                    windowWidth,
                    windowHeight,
                    targetWidth,
                    targetHeight,
                    RendererSettings.internalResolution(),
                    RendererSettings.giQuality().samples(),
                    RendererSettings.shadowQuality().samples(),
                    RendererSettings.rayDistance(),
                    RendererSettings.reflectionsEnabled(),
                    RendererSettings.reflectionBounces(),
                    RendererSettings.reflectionDistance(),
                    RendererSettings.temporalQuality(),
                    RendererSettings.denoiseQuality().radius()
            );
        }

        if (resources == null || resources.width != capacityWidth || resources.height != capacityHeight) {
            destroyResourcesIfSafe();
            resources = Resources.create(device, capacityWidth, capacityHeight);
            sceneUploadRequired = true;
            sceneSignature = Long.MIN_VALUE;
            ready = false;
        }
        activeRenderWidth = targetWidth;
        activeRenderHeight = targetHeight;

        // Resources/bootstrap exist only to give the full P12-P18 pipeline a live Vulkan scene
        // to compile against. Do not expose the simplified bootstrap renderer to the player.
        // Minecraft's own renderer (and the player's currently selected resource pack) stays
        // visible until the complete material-aware base pipeline is ready.
        if (!P12FullBasePipeline.ready()) {
            ready = false;
            historyValid = false;
            historyReadIndex = -1;
            lastCompletedFrame = null;
            lastCompletedMode = null;
            return;
        }

        if (!frame.dimensionId().equals(dimensionId)) {
            resetSceneSlots();
            dimensionId = frame.dimensionId();
            sceneUploadRequired = true;
            sceneSignature = Long.MIN_VALUE;
            ready = false;
        }

        long newSignature = sectionSignature(sections);
        if (newSignature != sceneSignature) {
            syncStableSlots(sections);
            sceneSignature = newSignature;
            sceneUploadRequired = true;
        }

        Resources r = resources;
        boolean fullSceneUpload = sceneUploadRequired;
        DebugMode submittedMode = mode;
        boolean canReuseHistory = usesTemporalHistory(submittedMode)
                && historyValid
                && historyCompatible(submittedMode, lastCompletedMode)
                && lastCompletedFrame != null
                && !fullSceneUpload
                && frame.dimensionId().equals(lastCompletedFrame.dimensionId());
        int historyWriteIndex = historyReadIndex < 0 ? 0 : 1 - historyReadIndex;

        ByteBuffer upload = r.upload.mappedView();
        packHeader(
                upload,
                frame,
                r,
                sections.size(),
                submittedMode,
                canReuseHistory ? lastCompletedFrame : null,
                canReuseHistory ? historyReadIndex : -1,
                historyWriteIndex,
                activeRenderWidth,
                activeRenderHeight
        );

        int uploadBytes;
        if (fullSceneUpload) {
            packLookupSectionsAndLights(upload, sections);
            uploadBytes = STATIC_DATA_END_WORD * Integer.BYTES;
        } else {
            uploadBytes = CAMERA_UPLOAD_WORDS * Integer.BYTES;
        }
        r.upload.flush(0, uploadBytes);

        sceneUploadRequired = false;
        inFlight = true;
        lastSubmittedFrame = frame.frameIndex();

        try {
            long submitStartedNanos = System.nanoTime();
            VulkanFrameComputeBatch batch = VulkanFrameComputeBatch.begin(device, capabilities, r.commandPool);
            recordCommands(
                    batch.commandBuffer(),
                    r,
                    uploadBytes,
                    activeRenderWidth,
                    activeRenderHeight
            );
            batch.finishAndEnqueue(() -> onFrameComplete(
                    fullSceneUpload,
                    frame,
                    submittedMode,
                    historyWriteIndex,
                    submitStartedNanos
            ));
        } catch (Throwable failure) {
            inFlight = false;
            sceneUploadRequired |= fullSceneUpload;
            TotemLumenClient.LOGGER.error("P12 one-bounce GI frame submission failed", failure);
        }
    }

    public static void tickLifecycle(Minecraft client) {
        if (client.level == null) {
            ready = false;
            if (resources != null) {
                if (inFlight) {
                    resetRequested = true;
                } else {
                    destroyResourcesIfSafe();
                }
            }
        }
    }

    public static DebugMode cycleMode() {
        DebugMode[] values = DebugMode.values();
        return setMode(values[(mode.ordinal() + 1) % values.length]);
    }

    public static DebugMode cyclePerformanceProbeMode() {
        int current = -1;
        for (int i = 0; i < PERFORMANCE_PROBE_MODES.length; i++) {
            if (PERFORMANCE_PROBE_MODES[i] == mode) {
                current = i;
                break;
            }
        }
        return setMode(PERFORMANCE_PROBE_MODES[(current + 1) % PERFORMANCE_PROBE_MODES.length]);
    }

    public static DebugMode setMode(DebugMode nextMode) {
        if (nextMode == null) throw new IllegalArgumentException("nextMode is required");
        DebugMode previous = mode;
        mode = nextMode;
        if (!historyCompatible(mode, previous)) {
            historyValid = false;
        }
        performanceProbeMode = mode;
        performanceProbeWarmup = 0;
        performanceProbeSamples = 0;
        performanceProbeAccumulatedNanos = 0L;
        TotemLumenClient.LOGGER.info(
                "Performance probe RESET: mode={}, render={}x{}, giSamples={}, shadowSamples={}, reflections={}",
                mode.label(),
                activeRenderWidth,
                activeRenderHeight,
                RendererSettings.giQuality().samples(),
                RendererSettings.shadowQuality().samples(),
                RendererSettings.reflectionsEnabled()
        );
        return mode;
    }

    public static DebugMode mode() {
        return mode;
    }

    public static void drawHud(GuiGraphicsExtractor graphics) {
        if (!RendererSettings.rendererEnabled()) return;
        Resources r = resources;
        if (!P12FullBasePipeline.ready() || !ready || r == null || r.view.isClosed()) {
            // No Totem composite here: leaving the HUD untouched exposes Minecraft's normal
            // world render as the intentional startup/recompile fallback.
            P5WorldDebugComposite.drawHud(graphics);
            return;
        }

        graphics.blit(
                r.view,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST),
                0,
                0,
                graphics.guiWidth(),
                graphics.guiHeight(),
                0.0f,
                activeRenderWidth / (float) r.width,
                0.0f,
                activeRenderHeight / (float) r.height
        );
    }

    public static void shutdown() {
        ready = false;
        if (inFlight) {
            resetRequested = true;
        } else {
            destroyResourcesIfSafe();
        }
    }

    private static void onFrameComplete(
            boolean fullSceneUpload,
            FrameSnapshot submittedFrame,
            DebugMode submittedMode,
            int historyWriteIndex,
            long submitStartedNanos
    ) {
        inFlight = false;
        ready = true;
        lastCompletedFrame = submittedFrame;
        lastCompletedMode = submittedMode;
        if (usesTemporalHistory(submittedMode)) {
            historyReadIndex = historyWriteIndex;
            historyValid = true;
        } else {
            historyValid = false;
        }

        recordPerformanceProbe(submittedMode, submitStartedNanos);

        if (!firstFrameLogged) {
            firstFrameLogged = true;
            TotemLumenClient.LOGGER.info(
                    "P5 stable GPU lookup READY: {}x{}, slots={}/{}, lookupMaxProbe={}, mode={}, sceneUpload={}",
                    resources == null ? 0 : resources.width,
                    resources == null ? 0 : resources.height,
                    SLOT_ALLOCATOR.usedSlots(), SLOT_ALLOCATOR.capacity(), lastLookupMaxProbe,
                    submittedMode.label(), fullSceneUpload
            );
            TotemLumenClient.LOGGER.info(
                    "P6 hard shadow debug READY: secondary DDA visibility ray active toward fixed directional test light"
            );
        }
        if (!p7Logged && fullSceneUpload) {
            p7Logged = true;
            TotemLumenClient.LOGGER.info(
                    "P7 local light lists READY: lights={}, populatedLists={}/{}, maxLightsPerSection={}/{}, mode={}",
                    lastLightCount,
                    lastPopulatedLightLists,
                    SLOT_ALLOCATOR.usedSlots(),
                    lastMaxLightsPerSection,
                    GpuSectionLightLists.MAX_LIGHTS_PER_SECTION,
                    submittedMode.label()
            );
        }
        if (!p8Logged && fullSceneUpload) {
            p8Logged = true;
            TotemLumenClient.LOGGER.info(
                    "P8 emissive materials READY: emissiveMaterials={}, coloredLights={}, mode={}",
                    lastEmissiveMaterialCount,
                    lastLightCount,
                    submittedMode.label()
            );
        }
        if (!p9Logged) {
            p9Logged = true;
            TotemLumenClient.LOGGER.info(
                    "P9 soft shadows READY: samples={}, angularRadius={}, mode={}",
                    SOFT_SHADOW_SAMPLES,
                    SOFT_SHADOW_ANGULAR_RADIUS,
                    submittedMode.label()
            );
        }
        if (!p10Logged) {
            p10Logged = true;
            TotemLumenClient.LOGGER.info(
                    "P10 temporal history READY: pingPongBuffers=2, recordWords={}, historyWeight={}, rotatingSamples={}, mode={}",
                    HISTORY_RECORD_WORDS,
                    TEMPORAL_HISTORY_WEIGHT,
                    SOFT_SHADOW_SAMPLES,
                    submittedMode.label()
            );
        }
        if (!p11Logged) {
            p11Logged = true;
            TotemLumenClient.LOGGER.info(
                    "P11 spatial denoise READY: radius={}, kernel=3x3, edgeTests=material+normal+voxel, mode={}",
                    SPATIAL_DENOISE_RADIUS,
                    submittedMode.label()
            );
        }
        if (!p12Logged) {
            p12Logged = true;
            TotemLumenClient.LOGGER.info(
                    "P12 one-bounce GI READY: bounceSamplesPerFrame=1, maxDistance={}, strength={}, progressiveHistoryMaxSamples={}, spatial=true, mode={}",
                    GI_MAX_DISTANCE,
                    GI_STRENGTH,
                    GI_HISTORY_MAX_SAMPLES,
                    submittedMode.label()
            );
        }
        if (resetRequested) {
            resetRequested = false;
            destroyResourcesIfSafe();
        }
    }

    private static void recordPerformanceProbe(DebugMode submittedMode, long submitStartedNanos) {
        if (submittedMode != performanceProbeMode) {
            performanceProbeMode = submittedMode;
            performanceProbeWarmup = 0;
            performanceProbeSamples = 0;
            performanceProbeAccumulatedNanos = 0L;
        }

        long elapsedNanos = Math.max(0L, System.nanoTime() - submitStartedNanos);
        if (performanceProbeWarmup < PERFORMANCE_PROBE_WARMUP_FRAMES) {
            performanceProbeWarmup++;
            return;
        }

        performanceProbeAccumulatedNanos += elapsedNanos;
        performanceProbeSamples++;
        if (performanceProbeSamples < PERFORMANCE_PROBE_SAMPLE_FRAMES) return;

        double averageMs = performanceProbeAccumulatedNanos
                / (double) performanceProbeSamples
                / 1_000_000.0;
        double effectiveFps = averageMs <= 0.0 ? 0.0 : 1000.0 / averageMs;
        TotemLumenClient.LOGGER.info(
                "Performance probe RESULT: mode={}, render={}x{}, avgSubmitToCompleteMs={}, effectiveTotemFps={}, giSamples={}, shadowSamples={}, reflections={}, p17PackMs={}, p17UploadBytes={}",
                submittedMode.label(),
                activeRenderWidth,
                activeRenderHeight,
                String.format(java.util.Locale.ROOT, "%.3f", averageMs),
                String.format(java.util.Locale.ROOT, "%.1f", effectiveFps),
                RendererSettings.giQuality().samples(),
                RendererSettings.shadowQuality().samples(),
                RendererSettings.reflectionsEnabled(),
                String.format(
                        java.util.Locale.ROOT,
                        "%.3f",
                        P17DynamicEntityGpuUploader.lastPackNanos() / 1_000_000.0
                ),
                P17DynamicEntityGpuUploader.lastUploadBytes()
        );
        performanceProbeSamples = 0;
        performanceProbeAccumulatedNanos = 0L;
    }

    private static List<SectionSnapshot> nearestSections(FrameSnapshot frame) {
        List<SectionSnapshot> snapshots = new ArrayList<>(SceneExtractionBridge.scene().sectionSnapshots());
        snapshots.removeIf(snapshot -> !snapshot.key().dimensionId().equals(frame.dimensionId()));
        snapshots.sort(Comparator.comparingDouble(snapshot -> distanceSquared(frame, snapshot)));
        if (snapshots.size() > MAX_SECTIONS) {
            snapshots = new ArrayList<>(snapshots.subList(0, MAX_SECTIONS));
        }
        return List.copyOf(snapshots);
    }

    private static double distanceSquared(FrameSnapshot frame, SectionSnapshot snapshot) {
        double x = snapshot.key().x() * 16.0 + 8.0 - frame.cameraX();
        double y = snapshot.key().y() * 16.0 + 8.0 - frame.cameraY();
        double z = snapshot.key().z() * 16.0 + 8.0 - frame.cameraZ();
        return x * x + y * y + z * z;
    }

    private static long sectionSignature(List<SectionSnapshot> sections) {
        long hash = 0xcbf29ce484222325L;
        for (SectionSnapshot snapshot : sections) {
            hash ^= snapshot.key().x();
            hash *= 0x100000001b3L;
            hash ^= snapshot.key().y();
            hash *= 0x100000001b3L;
            hash ^= snapshot.key().z();
            hash *= 0x100000001b3L;
            hash ^= snapshot.revision();
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static void syncStableSlots(List<SectionSnapshot> sections) {
        Set<SectionKey> selected = new HashSet<>();
        for (SectionSnapshot snapshot : sections) selected.add(snapshot.key());

        for (SectionKey resident : List.copyOf(RESIDENT_KEYS)) {
            if (!selected.contains(resident)) {
                SLOT_ALLOCATOR.remove(resident);
                RESIDENT_KEYS.remove(resident);
            }
        }
        for (SectionSnapshot snapshot : sections) {
            SLOT_ALLOCATOR.apply(snapshot);
            RESIDENT_KEYS.add(snapshot.key());
        }
    }

    private static void resetSceneSlots() {
        SLOT_ALLOCATOR.clear();
        RESIDENT_KEYS.clear();
        historyValid = false;
        historyReadIndex = -1;
        lastCompletedFrame = null;
        lastCompletedMode = null;
        lastLookupMaxProbe = 0;
        lastLightCount = 0;
        lastMaxLightsPerSection = 0;
        lastPopulatedLightLists = 0;
        lastEmissiveMaterialCount = 0;
        lastRuntimeTuningRevision = Long.MIN_VALUE;
        lastAnimationSignature = Long.MIN_VALUE;
        p7Logged = false;
        p8Logged = false;
        p9Logged = false;
        p10Logged = false;
        p11Logged = false;
        p12Logged = false;
    }

    private static void packHeader(
            ByteBuffer buffer,
            FrameSnapshot frame,
            Resources resources,
            int sectionCount,
            DebugMode debugMode,
            FrameSnapshot previousFrame,
            int historyReadIndex,
            int historyWriteIndex,
            int renderWidth,
            int renderHeight
    ) {
        putWord(buffer, 1, sectionCount);
        putWord(buffer, 2, LOOKUP_CAPACITY);
        putWord(buffer, 3, resources.pixelBaseWord);
        putWord(buffer, 4, renderWidth);
        putWord(buffer, 5, renderHeight);
        putWord(buffer, 6, MAX_STEPS);
        putWord(buffer, 7, Float.floatToRawIntBits((float) RendererSettings.rayDistance()));
        putWord(buffer, 8, Float.floatToRawIntBits((float) frame.cameraX()));
        putWord(buffer, 9, Float.floatToRawIntBits((float) frame.cameraY()));
        putWord(buffer, 10, Float.floatToRawIntBits((float) frame.cameraZ()));

        float[][] basis = cameraBasis(frame);
        putVec3(buffer, 11, basis[0]);
        putVec3(buffer, 14, basis[1]);
        putVec3(buffer, 17, basis[2]);
        putWord(buffer, 20, Float.floatToRawIntBits((float) Math.tan(Math.toRadians(frame.fovDegrees()) * 0.5)));
        putWord(buffer, 21, Float.floatToRawIntBits(renderWidth / (float) renderHeight));
        putWord(buffer, 22, debugMode.shaderValue);
        putWord(buffer, 24, historyReadIndex < 0 ? 0 : resources.historyBaseWord(historyReadIndex));
        putWord(buffer, 25, resources.historyBaseWord(historyWriteIndex));
        putWord(buffer, 26, previousFrame == null ? 0 : 1);

        if (previousFrame == null) {
            for (int word = 27; word <= 40; word++) putWord(buffer, word, 0);
        } else {
            putWord(buffer, 27, Float.floatToRawIntBits((float) previousFrame.cameraX()));
            putWord(buffer, 28, Float.floatToRawIntBits((float) previousFrame.cameraY()));
            putWord(buffer, 29, Float.floatToRawIntBits((float) previousFrame.cameraZ()));
            float[][] previousBasis = cameraBasis(previousFrame);
            putVec3(buffer, 30, previousBasis[0]);
            putVec3(buffer, 33, previousBasis[1]);
            putVec3(buffer, 36, previousBasis[2]);
            putWord(
                    buffer,
                    39,
                    Float.floatToRawIntBits((float) Math.tan(Math.toRadians(previousFrame.fovDegrees()) * 0.5))
            );
            putWord(buffer, 40, Float.floatToRawIntBits(renderWidth / (float) renderHeight));
        }
        putWord(buffer, 41, (int) frame.frameIndex());
        putWord(buffer, 42, RendererSettings.reflectionBounces());
        putWord(buffer, 43, Float.floatToRawIntBits((float) RendererSettings.reflectionDistance()));
        putWord(buffer, 44, RendererSettings.giQuality().samples());
        putWord(buffer, 45, RendererSettings.shadowQuality().samples());
        putWord(buffer, 46, RendererSettings.reflectionsEnabled() ? 1 : 0);
        putWord(buffer, 47, RendererSettings.waterReflections() ? 1 : 0);
        putWord(buffer, 48, RendererSettings.temporalQuality().historySamples());
        putWord(buffer, 49, Float.floatToRawIntBits(RendererSettings.temporalQuality().directHistoryWeight()));
        putWord(buffer, 50, RendererSettings.denoiseQuality().radius());
        putWord(buffer, 51, resources.width);
        putWord(buffer, 52, resources.height);
        putWord(buffer, 53, (int) currentAnimationTick(frame));

        RendererRuntimeTuningRegistry.ensureLoaded(Minecraft.getInstance().getResourceManager());
        var tuning = RendererRuntimeTuningRegistry.current();
        putWord(buffer, 54, Float.floatToRawIntBits(tuning.localLightGain()));
        putWord(buffer, 55, Float.floatToRawIntBits(tuning.reflectionSpreadScale()));
        putWord(buffer, 56, Float.floatToRawIntBits(tuning.reflectionRoughnessEnergy()));
        putWord(buffer, 57, Float.floatToRawIntBits(tuning.reflectionDielectricEnergy()));
        putWord(buffer, 58, Float.floatToRawIntBits(tuning.reflectionMetalEnergy()));
        putWord(buffer, 59, Float.floatToRawIntBits(tuning.reflectionNormalBias()));
        putWord(buffer, 60, Float.floatToRawIntBits(tuning.reflectionDirectionBias()));
        putWord(buffer, 61, tuning.transmissionMaxLayers());
        putWord(buffer, 62, Float.floatToRawIntBits(tuning.transmissionExitEpsilon()));
        putWord(buffer, 63, Float.floatToRawIntBits(tuning.transmissionMinScalar()));
        putWord(buffer, 64, Float.floatToRawIntBits(tuning.waterTintMix()));
        putWord(buffer, 65, Float.floatToRawIntBits(tuning.waterTransmissionScalar()));
        putWord(buffer, 66, Float.floatToRawIntBits(tuning.waterFallbackR()));
        putWord(buffer, 67, Float.floatToRawIntBits(tuning.waterFallbackG()));
        putWord(buffer, 68, Float.floatToRawIntBits(tuning.waterFallbackB()));
        putWord(buffer, 69, Float.floatToRawIntBits(tuning.waterMaterialR()));
        putWord(buffer, 70, Float.floatToRawIntBits(tuning.waterMaterialG()));
        putWord(buffer, 71, Float.floatToRawIntBits(tuning.waterMaterialB()));
        putWord(buffer, 72, Float.floatToRawIntBits(tuning.lavaMaterialR()));
        putWord(buffer, 73, Float.floatToRawIntBits(tuning.lavaMaterialG()));
        putWord(buffer, 74, Float.floatToRawIntBits(tuning.lavaMaterialB()));
        putWord(buffer, 75, Float.floatToRawIntBits(tuning.otherFluidR()));
        putWord(buffer, 76, Float.floatToRawIntBits(tuning.otherFluidG()));
        putWord(buffer, 77, Float.floatToRawIntBits(tuning.otherFluidB()));
        putWord(buffer, 78, Float.floatToRawIntBits(tuning.lavaEmissionR()));
        putWord(buffer, 79, Float.floatToRawIntBits(tuning.lavaEmissionG()));
        putWord(buffer, 80, Float.floatToRawIntBits(tuning.lavaEmissionB()));
        putWord(buffer, 81, Float.floatToRawIntBits(tuning.lavaEmissionStrength()));
        putWord(buffer, 82, Float.floatToRawIntBits(tuning.localAmbient()));
        putWord(buffer, 83, Float.floatToRawIntBits(tuning.surfaceEmissionGain()));
        putWord(buffer, 84, Float.floatToRawIntBits(tuning.giDisplayGain()));
        putWord(buffer, 85, Float.floatToRawIntBits(tuning.waterReflectionRoughness()));
        putWord(buffer, 86, Float.floatToRawIntBits(tuning.lavaReflectionRoughness()));
        putWord(buffer, 87, Float.floatToRawIntBits(tuning.waterReflectionScale()));
        putWord(buffer, 88, Float.floatToRawIntBits(tuning.lavaReflectionScale()));
        putWord(buffer, 89, Float.floatToRawIntBits(tuning.entityEmissiveGain()));
        for (int word = 90; word < HEADER_WORDS; word++) putWord(buffer, word, 0);
    }

    private static long currentAnimationTick(FrameSnapshot frame) {
        return Minecraft.getInstance().level == null
                ? frame.frameIndex()
                : Minecraft.getInstance().level.getGameTime();
    }

    private static void packLookupSectionsAndLights(ByteBuffer buffer, List<SectionSnapshot> sections) {
        GpuSectionLookupTable lookup = new GpuSectionLookupTable(LOOKUP_CAPACITY);
        for (SectionSnapshot snapshot : sections) {
            int slot = SLOT_ALLOCATOR.slotFor(snapshot.key());
            if (slot < 0) {
                throw new IllegalStateException("Missing stable GPU slot for " + snapshot.key());
            }
            lookup.put(snapshot.key().x(), snapshot.key().y(), snapshot.key().z(), slot);
            int voxelOffset = VOXEL_BASE_WORD + slot * SectionVoxelData.VOXEL_COUNT;
            int[] materialIds = snapshot.voxels().copyMaterialIds();
            for (int voxel = 0; voxel < materialIds.length; voxel++) {
                putWord(buffer, voxelOffset + voxel, materialIds[voxel]);
            }
        }
        lookup.writeTo(buffer, LOOKUP_BASE_WORD);
        lastLookupMaxProbe = lookup.maxProbe();

        for (int word = SECTION_LIGHT_COUNT_BASE_WORD; word < STATIC_DATA_END_WORD; word++) {
            putWord(buffer, word, 0);
        }

        var materialDefinitions = SceneExtractionBridge.materials().snapshot();
        if (materialDefinitions.size() > MAX_MATERIALS) {
            throw new IllegalStateException(
                    "P8 material table capacity exceeded: " + materialDefinitions.size() + "/" + MAX_MATERIALS
            );
        }
        putWord(buffer, 23, materialDefinitions.size());
        lastEmissiveMaterialCount = 0;
        for (int materialId = 0; materialId < materialDefinitions.size(); materialId++) {
            var material = materialDefinitions.get(materialId);
            int base = MATERIAL_EMISSION_BASE_WORD + materialId * MATERIAL_EMISSION_WORDS_PER_RECORD;
            putWord(buffer, base, Float.floatToRawIntBits(material.emissionR()));
            putWord(buffer, base + 1, Float.floatToRawIntBits(material.emissionG()));
            putWord(buffer, base + 2, Float.floatToRawIntBits(material.emissionB()));
            putWord(buffer, base + 3, Float.floatToRawIntBits(material.emissionLevel() / 15.0f));
            putWord(buffer, base + 4, Float.floatToRawIntBits(material.transmissionR()));
            putWord(buffer, base + 5, Float.floatToRawIntBits(material.transmissionG()));
            putWord(buffer, base + 6, Float.floatToRawIntBits(material.transmissionB()));
            putWord(buffer, base + 7, Float.floatToRawIntBits(material.opacity()));
            putWord(buffer, base + 8, Float.floatToRawIntBits(material.indexOfRefraction()));
            putWord(buffer, base + 9, Float.floatToRawIntBits(material.roughness()));
            putWord(buffer, base + 10, Float.floatToRawIntBits(material.metallic()));
            putWord(buffer, base + 11, material.flags());
            putWord(buffer, base + 12, Float.floatToRawIntBits(material.lightRadiusScale()));
            putWord(buffer, base + 13, Float.floatToRawIntBits(material.lightIntensityScale()));
            putWord(buffer, base + 14, Float.floatToRawIntBits(material.reflectionScale()));
            putWord(buffer, base + 15, material.lightEmitterAnchor());
            if (material.emissionLevel() > 0) {
                lastEmissiveMaterialCount++;
            }
        }

        var lightLists = GpuSectionLightLists.build(
                sections,
                MAX_SECTIONS,
                SLOT_ALLOCATOR::slotFor,
                (GpuSectionLightLists.EmissionResolver) materialId -> {
                    if (materialId < 0 || materialId >= materialDefinitions.size()) {
                        return new GpuSectionLightLists.Emission(0, 0.0f, 0.0f, 0.0f);
                    }
                    var material = materialDefinitions.get(materialId);
                    return new GpuSectionLightLists.Emission(
                            material.emissionLevel(),
                            material.emissionR(),
                            material.emissionG(),
                            material.emissionB(),
                            material.lightRadiusScale(),
                            material.lightIntensityScale(),
                            material.pointLightEmitter(),
                            material.lightEmitterX(),
                            material.lightEmitterY(),
                            material.lightEmitterZ()
                    );
                }
        );

        int[] counts = lightLists.countsBySlot();
        int[][] indices = lightLists.indicesBySlot();
        for (int slot = 0; slot < MAX_SECTIONS; slot++) {
            putWord(buffer, SECTION_LIGHT_COUNT_BASE_WORD + slot, counts[slot]);
            for (int localIndex = 0; localIndex < counts[slot]; localIndex++) {
                putWord(
                        buffer,
                        SECTION_LIGHT_INDEX_BASE_WORD + slot * GpuSectionLightLists.MAX_LIGHTS_PER_SECTION + localIndex,
                        indices[slot][localIndex]
                );
            }
        }

        List<GpuSectionLightLists.PointLight> lights = lightLists.lights();
        for (int lightIndex = 0; lightIndex < lights.size(); lightIndex++) {
            GpuSectionLightLists.PointLight light = lights.get(lightIndex);
            int base = LIGHT_DATA_BASE_WORD + lightIndex * LIGHT_WORDS_PER_RECORD;
            putWord(buffer, base, Float.floatToRawIntBits(light.x()));
            putWord(buffer, base + 1, Float.floatToRawIntBits(light.y()));
            putWord(buffer, base + 2, Float.floatToRawIntBits(light.z()));
            putWord(buffer, base + 3, Float.floatToRawIntBits(light.radius()));
            putWord(buffer, base + 4, Float.floatToRawIntBits(light.r()));
            putWord(buffer, base + 5, Float.floatToRawIntBits(light.g()));
            putWord(buffer, base + 6, Float.floatToRawIntBits(light.b()));
            float encodedIntensity = light.pointEmitter()
                    ? -Math.max(light.intensity(), Float.MIN_NORMAL)
                    : light.intensity();
            putWord(buffer, base + 7, Float.floatToRawIntBits(encodedIntensity));
        }

        lastLightCount = lights.size();
        lastMaxLightsPerSection = lightLists.maxLightsInSection();
        lastPopulatedLightLists = lightLists.populatedLists();
    }

    private static float[][] cameraBasis(FrameSnapshot frame) {
        return new float[][]{
                rotate(frame, 1.0f, 0.0f, 0.0f),
                rotate(frame, 0.0f, 1.0f, 0.0f),
                rotate(frame, 0.0f, 0.0f, -1.0f)
        };
    }

    private static float[] rotate(FrameSnapshot frame, float vx, float vy, float vz) {
        float qx = frame.rotationX();
        float qy = frame.rotationY();
        float qz = frame.rotationZ();
        float qw = frame.rotationW();
        float tx = 2.0f * (qy * vz - qz * vy);
        float ty = 2.0f * (qz * vx - qx * vz);
        float tz = 2.0f * (qx * vy - qy * vx);
        float rx = vx + qw * tx + (qy * tz - qz * ty);
        float ry = vy + qw * ty + (qz * tx - qx * tz);
        float rz = vz + qw * tz + (qx * ty - qy * tx);
        float length = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (length < 0.000001f) return new float[]{vx, vy, vz};
        return new float[]{rx / length, ry / length, rz / length};
    }

    private static void putVec3(ByteBuffer buffer, int base, float[] value) {
        putWord(buffer, base, Float.floatToRawIntBits(value[0]));
        putWord(buffer, base + 1, Float.floatToRawIntBits(value[1]));
        putWord(buffer, base + 2, Float.floatToRawIntBits(value[2]));
    }

    private static void putWord(ByteBuffer buffer, int wordIndex, int value) {
        buffer.putInt(wordIndex * Integer.BYTES, value);
    }

    private static void recordCommands(
            VkCommandBuffer commandBuffer,
            Resources r,
            int uploadBytes,
            int renderWidth,
            int renderHeight
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer uploadCopy = VkBufferCopy.calloc(1, stack);
            uploadCopy.get(0).srcOffset(0).dstOffset(0).size(uploadBytes);
            VK10.vkCmdCopyBuffer(commandBuffer, r.upload.vkBuffer(), r.scene.vkBuffer(), uploadCopy);

            VkBufferMemoryBarrier.Buffer uploadToCompute = VkBufferMemoryBarrier.calloc(1, stack);
            uploadToCompute.get(0)
                    .sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(r.scene.vkBuffer())
                    .offset(0)
                    .size(r.totalBytes);
            VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT | VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, null, uploadToCompute, null
            );

            VK10.vkCmdBindPipeline(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, r.program.pipeline());
            VK10.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    r.program.pipelineLayout(),
                    0,
                    stack.longs(r.program.descriptorSet()),
                    null
            );
            VK10.vkCmdDispatch(commandBuffer, (renderWidth + 7) / 8, (renderHeight + 7) / 8, 1);

            long pixelOffset = (long) r.pixelBaseWord * Integer.BYTES;
            VkBufferMemoryBarrier.Buffer computeToCopy = VkBufferMemoryBarrier.calloc(1, stack);
            computeToCopy.get(0)
                    .sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(r.scene.vkBuffer())
                    .offset(pixelOffset)
                    .size((long) renderWidth * renderHeight * Integer.BYTES);
            VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, null, computeToCopy, null
            );

            VkImageMemoryBarrier.Buffer imageForCopy = VkImageMemoryBarrier.calloc(1, stack);
            imageForCopy.get(0)
                    .sType$Default()
                    .srcAccessMask(ready ? VK10.VK_ACCESS_SHADER_READ_BIT : 0)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(r.vkImage);
            imageForCopy.get(0).subresourceRange()
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    ready ? VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT : VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, null, null, imageForCopy
            );

            VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
            copy.get(0)
                    .bufferOffset(pixelOffset)
                    .bufferRowLength(renderWidth)
                    .bufferImageHeight(renderHeight);
            copy.get(0).imageSubresource()
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            copy.get(0).imageOffset().set(0, 0, 0);
            copy.get(0).imageExtent().set(renderWidth, renderHeight, 1);
            VK10.vkCmdCopyBufferToImage(
                    commandBuffer, r.scene.vkBuffer(), r.vkImage,
                    VK10.VK_IMAGE_LAYOUT_GENERAL, copy
            );

            VkImageMemoryBarrier.Buffer imageReady = VkImageMemoryBarrier.calloc(1, stack);
            imageReady.get(0)
                    .sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(r.vkImage);
            imageReady.get(0).subresourceRange()
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0, null, null, imageReady
            );
        }
    }

    private static void destroyResourcesIfSafe() {
        if (resources == null || inFlight) return;
        Resources old = resources;
        resources = null;
        closeQuietly(old.view);
        closeQuietly(old.texture);
        closeQuietly(old.program);
        closeQuietly(old.upload);
        closeQuietly(old.scene);
        closeQuietly(old.commandPool);
        resetSceneSlots();
        ready = false;
        sceneUploadRequired = true;
        sceneSignature = Long.MIN_VALUE;
        lastSubmittedFrame = -1;
        dimensionId = null;
        lastSettingsRevision = Long.MIN_VALUE;
        activeRenderWidth = 0;
        activeRenderHeight = 0;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception exception) {
            TotemLumenClient.LOGGER.warn("Failed to close P12 one-bounce GI resource", exception);
        }
    }

    private static final class Resources {
        final int width;
        final int height;
        final int history0BaseWord;
        final int history1BaseWord;
        final int pixelBaseWord;
        final int pixelBytes;
        final int totalBytes;
        final VulkanOwnedBuffer upload;
        final VulkanOwnedBuffer scene;
        final VulkanComputeProgram program;
        final VulkanFrameCommandPool commandPool;
        final GpuTexture texture;
        final GpuTextureView view;
        final long vkImage;

        private Resources(
                int width,
                int height,
                int history0BaseWord,
                int history1BaseWord,
                int pixelBaseWord,
                int pixelBytes,
                int totalBytes,
                VulkanOwnedBuffer upload,
                VulkanOwnedBuffer scene,
                VulkanComputeProgram program,
                VulkanFrameCommandPool commandPool,
                GpuTexture texture,
                GpuTextureView view,
                long vkImage
        ) {
            this.width = width;
            this.height = height;
            this.history0BaseWord = history0BaseWord;
            this.history1BaseWord = history1BaseWord;
            this.pixelBaseWord = pixelBaseWord;
            this.pixelBytes = pixelBytes;
            this.totalBytes = totalBytes;
            this.upload = upload;
            this.scene = scene;
            this.program = program;
            this.commandPool = commandPool;
            this.texture = texture;
            this.view = view;
            this.vkImage = vkImage;
        }

        int historyBaseWord(int index) {
            return index == 0 ? history0BaseWord : history1BaseWord;
        }

        static Resources create(VulkanDevice device, int width, int height) {
            int pixelCount = Math.multiplyExact(width, height);
            int historyWords = Math.multiplyExact(pixelCount, HISTORY_RECORD_WORDS);
            int history0BaseWord = STATIC_DATA_END_WORD;
            int history1BaseWord = Math.addExact(history0BaseWord, historyWords);
            int pixelBaseWord = Math.addExact(history1BaseWord, historyWords);
            int pixelBytes = Math.multiplyExact(pixelCount, Integer.BYTES);
            int totalWords = Math.addExact(pixelBaseWord, pixelCount);
            int totalBytes = Math.multiplyExact(totalWords, Integer.BYTES);

            VulkanOwnedBuffer upload = null;
            VulkanOwnedBuffer scene = null;
            VulkanComputeProgram program = null;
            VulkanFrameCommandPool commandPool = null;
            GpuTexture texture = null;
            GpuTextureView view = null;
            try {
                upload = VulkanOwnedBuffer.createUpload(device, totalBytes);
                scene = VulkanOwnedBuffer.createStorage(device, totalBytes);
                program = VulkanComputeProgram.create(device, "totem_lumen_p12_one_bounce_gi.comp", SHADER, scene);
                commandPool = new VulkanFrameCommandPool(device);
                texture = RenderSystem.getDevice().createTexture(
                        "Totem Lumen P12 one-bounce GI target",
                        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                        GpuFormat.RGBA8_UNORM,
                        width, height, 1, 1
                );
                view = RenderSystem.getDevice().createTextureView(texture);
                if (!(texture instanceof VulkanGpuTexture vulkanTexture)) {
                    throw new IllegalStateException("P12 one-bounce GI target is not backed by VulkanGpuTexture");
                }
                return new Resources(
                        width,
                        height,
                        history0BaseWord,
                        history1BaseWord,
                        pixelBaseWord,
                        pixelBytes,
                        totalBytes,
                        upload,
                        scene,
                        program,
                        commandPool,
                        texture,
                        view,
                        vulkanTexture.vkImage()
                );
            } catch (Throwable failure) {
                closeQuietly(view);
                closeQuietly(texture);
                closeQuietly(program);
                closeQuietly(upload);
                closeQuietly(scene);
                closeQuietly(commandPool);
                throw failure;
            }
        }
    }
}
