package dev.totem.lumen.vulkan;

import java.lang.reflect.Field;

/**
 * Builds the P16 reflection compute pass from the already-proven P12-P15 shader helpers.
 *
 * <p>The pass intentionally extracts only scene lookup/geometry tracing, small material helpers,
 * P15 filtered transmission and P13 environment lighting. Temporal accumulation, denoising,
 * diffuse GI integration, local-light debug code and the original main function are excluded so
 * MoltenVK/Metal never has to compile the old P12-P16 monolithic control flow as one pipeline.</p>
 */
final class P16ReflectionPassShader {
    static final String SHADER_NAME = "totem_lumen_p16_reflection.comp";

    private P16ReflectionPassShader() {
    }

    static String build() {
        String transformed = baseP12ToP15Source();

        String geometry = span(
                transformed,
                "int floorDiv16(int value) {",
                "uint packRgba(vec3 rgb, uint alpha) {",
                "geometry/trace helpers"
        );
        String materialHelpers = span(
                transformed,
                "uint packRgba(vec3 rgb, uint alpha) {",
                "vec3 directionalSampleDirection(uint sampleIndex) {",
                "material/random helpers"
        );
        String environment = span(
                transformed,
                "uint p13EnvironmentCode() {",
                "vec3 p13OneBounceIndirectRgb(",
                "P13/P15 environment helpers"
        );

        return """
                #version 450
                layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
                layout(set = 0, binding = 0, std430) buffer SceneBuffer {
                    uint data[];
                } scene;

                const uint LOOKUP_BASE = 96u;
                const uint LOOKUP_CAPACITY = 256u;
                const uint LOOKUP_MASK = 255u;
                const uint LOOKUP_WORDS_PER_BUCKET = 4u;
                const uint VOXEL_BASE = 1120u;
                const uint VOXELS_PER_SECTION = 4096u;
                const uint MATERIAL_EMISSION_BASE = 528608u;
                const uint MATERIAL_EMISSION_WORDS_PER_RECORD = 16u;
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

                """
                + geometry
                + materialHelpers
                + environment
                + """

                const uint P16_HISTORY_RECORD_WORDS = 8u;
                const uint P16_HISTORY_MATERIAL_MASK = 0x0000FFFFu;
                const uint P16_HISTORY_SAMPLE_MASK = 0x00FF0000u;
                const uint P16_HISTORY_VERSION_MASK = 0x0F000000u;
                const uint P16_HISTORY_COVERAGE_COHORT_FLAG = 0x10000000u;
                const uint P16_HISTORY_ABI_VERSION = 2u;

                uint p16HistorySampleCount(uint historyBase) {
                    return (scene.data[historyBase + 7u] & P16_HISTORY_SAMPLE_MASK) >> 16u;
                }

                bool p16HistoryIdentityMatches(uint previousBase, uint currentBase) {
                    uint previousControl = scene.data[previousBase + 7u];
                    uint currentControl = scene.data[currentBase + 7u];
                    if (((previousControl & P16_HISTORY_VERSION_MASK) >> 24u) != P16_HISTORY_ABI_VERSION
                            || ((currentControl & P16_HISTORY_VERSION_MASK) >> 24u) != P16_HISTORY_ABI_VERSION) {
                        return false;
                    }

                    bool previousCoverage = (previousControl & P16_HISTORY_COVERAGE_COHORT_FLAG) != 0u;
                    bool currentCoverage = (currentControl & P16_HISTORY_COVERAGE_COHORT_FLAG) != 0u;
                    if (previousCoverage != currentCoverage) return false;
                    if (currentCoverage) {
                        return scene.data[previousBase + 5u] != 0u
                                && scene.data[previousBase + 5u] == scene.data[currentBase + 5u];
                    }

                    return (previousControl & P16_HISTORY_MATERIAL_MASK)
                                    == (currentControl & P16_HISTORY_MATERIAL_MASK)
                            && scene.data[previousBase + 1u] == scene.data[currentBase + 1u]
                            && scene.data[previousBase + 2u] == scene.data[currentBase + 2u]
                            && scene.data[previousBase + 3u] == scene.data[currentBase + 3u]
                            && scene.data[previousBase + 4u] == scene.data[currentBase + 4u];
                }

                vec3 p16PreviousRayDirection(uvec2 pixel, uint width, uint height) {
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
                    float ndcX = ((float(pixel.x) + 0.5) / float(width)) * 2.0 - 1.0;
                    float ndcY = 1.0 - ((float(pixel.y) + 0.5) / float(height)) * 2.0;
                    return normalize(
                        previousForward
                        + previousRight * (ndcX * previousAspect * previousTanHalfFov)
                        + previousUp * (ndcY * previousTanHalfFov)
                    );
                }

                bool p16ReprojectAnchor(
                        vec3 currentAnchor,
                        uint width,
                        uint height,
                        out uvec2 previousPixel
                ) {
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
                    vec3 relative = currentAnchor - previousOrigin;
                    float depth = dot(relative, previousForward);
                    if (depth <= 0.001 || previousTanHalfFov <= 0.000001 || previousAspect <= 0.000001) {
                        return false;
                    }
                    float ndcX = dot(relative, previousRight) / (depth * previousAspect * previousTanHalfFov);
                    float ndcY = dot(relative, previousUp) / (depth * previousTanHalfFov);
                    if (abs(ndcX) > 1.0 || abs(ndcY) > 1.0) return false;
                    ivec2 rounded = ivec2(floor(vec2(
                        (ndcX * 0.5 + 0.5) * float(width),
                        (0.5 - ndcY * 0.5) * float(height)
                    )));
                    if (rounded.x < 0 || rounded.y < 0
                            || rounded.x >= int(width) || rounded.y >= int(height)) return false;
                    previousPixel = uvec2(rounded);
                    return true;
                }

                bool p16ResolveHistoryBase(
                        uvec2 pixel,
                        uint width,
                        uint height,
                        vec3 currentOrigin,
                        vec3 currentDirection,
                        uint currentBase,
                        out uint previousBase
                ) {
                    float currentDistance = uintBitsToFloat(scene.data[currentBase + 6u]);
                    if (!(currentDistance > 0.0)) {
                        // A strict sky miss has no finite world-space anchor. Reuse only the
                        // same pixel when its previous/current sky rays are effectively
                        // identical; camera rotation therefore resets immediately. Fractional
                        // accepted/rejected misses carry a cohort anchor and use the path below.
                        uint currentControl = scene.data[currentBase + 7u];
                        if (scene.data[26] == 0u
                                || (currentControl & P16_HISTORY_COVERAGE_COHORT_FLAG) != 0u
                                || (currentControl & P16_HISTORY_MATERIAL_MASK) != 0u) return false;
                        previousBase = scene.data[24]
                                + (pixel.y * width + pixel.x) * P16_HISTORY_RECORD_WORDS;
                        if (!p16HistoryIdentityMatches(previousBase, currentBase)) return false;
                        vec3 previousDirection = p16PreviousRayDirection(pixel, width, height);
                        return dot(previousDirection, currentDirection) >= 0.999999;
                    }
                    vec3 currentAnchor = currentOrigin + currentDirection * currentDistance;
                    uvec2 previousPixel;
                    if (!p16ReprojectAnchor(currentAnchor, width, height, previousPixel)) return false;
                    previousBase = scene.data[24]
                            + (previousPixel.y * width + previousPixel.x) * P16_HISTORY_RECORD_WORDS;
                    if (!p16HistoryIdentityMatches(previousBase, currentBase)) return false;

                    float previousDistance = uintBitsToFloat(scene.data[previousBase + 6u]);
                    if (!(previousDistance > 0.0)) return false;
                    vec3 previousOrigin = vec3(
                        uintBitsToFloat(scene.data[27]),
                        uintBitsToFloat(scene.data[28]),
                        uintBitsToFloat(scene.data[29])
                    );
                    vec3 previousAnchor = previousOrigin
                            + p16PreviousRayDirection(previousPixel, width, height) * previousDistance;
                    float pixelFootprint = 2.0 * max(currentDistance, previousDistance)
                            * max(uintBitsToFloat(scene.data[20]), uintBitsToFloat(scene.data[39]))
                            / max(float(height), 1.0);
                    float anchorTolerance = clamp(pixelFootprint * 1.5, 0.025, 0.35);
                    return distance(currentAnchor, previousAnchor) <= anchorTolerance;
                }

                vec3 p16ResolveFinalRadiance(
                        uvec2 pixel,
                        uint width,
                        uint height,
                        vec3 currentOrigin,
                        vec3 currentDirection,
                        vec3 currentRadiance
                ) {
                    uint currentBase = scene.data[25]
                            + (pixel.y * width + pixel.x) * P16_HISTORY_RECORD_WORDS;
                    uint sampleCount = 1u;
                    vec3 resolved = currentRadiance;
                    uint previousBase;
                    if (scene.data[48] > 1u && p16ResolveHistoryBase(
                            pixel,
                            width,
                            height,
                            currentOrigin,
                            currentDirection,
                            currentBase,
                            previousBase
                    )) {
                        uint historyLimit = max(scene.data[48], 1u);
                        uint previousSamples = clamp(p16HistorySampleCount(previousBase), 1u, historyLimit);
                        sampleCount = min(previousSamples + 1u, historyLimit);
                        float historyWeight = float(previousSamples) / float(previousSamples + 1u);
                        resolved = mix(currentRadiance, unpackRgb(scene.data[previousBase]), historyWeight);
                    }
                    scene.data[currentBase] = packRgba(resolved, 255u);
                    scene.data[currentBase + 7u] = (scene.data[currentBase + 7u] & ~P16_HISTORY_SAMPLE_MASK)
                            | (min(sampleCount, 255u) << 16u);
                    return resolved;
                }

                vec3 p16SurfaceProperties(HitResult hit) {
                    if (hit.materialId < scene.data[23]) {
                        uint materialBase = MATERIAL_EMISSION_BASE
                                + hit.materialId * MATERIAL_EMISSION_WORDS_PER_RECORD;
                        return vec3(
                            clamp(uintBitsToFloat(scene.data[materialBase + 9u]), 0.0, 1.0),
                            clamp(uintBitsToFloat(scene.data[materialBase + 10u]), 0.0, 1.0),
                            max(uintBitsToFloat(scene.data[materialBase + 14u]), 0.0)
                        );
                    }

                    uint geometryCode = geometryAt(hit.voxel);
                    uint family = geometryCode & 0xF000u;
                    uint params = geometryCode & 0x0FFFu;
                    if (family == 0x9000u) {
                        float roughness = float(params & 0xFu) / 15.0;
                        float metallic = float((params >> 4u) & 0xFu) / 15.0;
                        return vec3(roughness, metallic, 1.0);
                    }
                    return vec3(0.80, 0.0, 1.0);
                }

                vec3 p16RoughReflectionDirection(
                        HitResult hit,
                        vec3 primaryDirection,
                        vec3 normal,
                        float roughness
                ) {
                    vec3 mirrorDirection = normalize(reflect(normalize(primaryDirection), normal));
                    uint state = hashBits(
                        uint(hit.voxel.x) * 0x9E3779B1u
                        ^ uint(hit.voxel.y) * 0x85EBCA77u
                        ^ uint(hit.voxel.z) * 0xC2B2AE3Du
                        ^ hit.materialId * 0x27D4EB2Du
                    );
                    float spread = roughness * roughness
                            * max(uintBitsToFloat(scene.data[55]), 0.0);
                    float radius = sqrt(max(random01(state), 0.0)) * spread;
                    float phi = 6.28318530718 * random01(state);
                    vec3 helper = abs(mirrorDirection.y) < 0.95
                            ? vec3(0.0, 1.0, 0.0)
                            : vec3(1.0, 0.0, 0.0);
                    vec3 tangent = normalize(cross(helper, mirrorDirection));
                    vec3 bitangent = normalize(cross(mirrorDirection, tangent));
                    vec3 roughDirection = normalize(
                        mirrorDirection
                        + tangent * (cos(phi) * radius)
                        + bitangent * (sin(phi) * radius)
                    );
                    return dot(roughDirection, normal) > 0.001 ? roughDirection : mirrorDirection;
                }

                vec3 p16ReflectionRgb(
                        HitResult primaryHit,
                        vec3 primaryOrigin,
                        vec3 primaryDirection
                ) {
                    uint maxBounces = min(scene.data[42], 2u);
                    if (maxBounces == 0u) return vec3(0.0);

                    float configuredDistance = uintBitsToFloat(scene.data[43]);
                    float reflectionDistance = min(
                        uintBitsToFloat(scene.data[7]),
                        clamp(configuredDistance, 1.0, 64.0)
                    );

                    vec3 accumulatedRadiance = vec3(0.0);
                    vec3 throughput = vec3(1.0);
                    HitResult currentHit = primaryHit;
                    vec3 currentOrigin = primaryOrigin;
                    vec3 currentDirection = primaryDirection;

                    for (uint bounce = 0u; bounce < 2u; bounce++) {
                        if (bounce >= maxBounces) break;

                        P18SurfaceSample p18Surface = p18ResolveSurface(
                            currentHit,
                            currentOrigin,
                            currentDirection
                        );
                        vec3 fallbackSurface = p16SurfaceProperties(currentHit);
                        float roughness = p18Surface.textured != 0u
                                ? p18Surface.roughness
                                : fallbackSurface.x;
                        float metallic = p18Surface.textured != 0u
                                ? p18Surface.metallic
                                : fallbackSurface.y;
                        float reflectionScale = p18Surface.textured != 0u
                                ? p18Surface.reflectionScale
                                : fallbackSurface.z;
                        vec3 normal = p18Surface.textured != 0u
                                ? p18Surface.normal
                                : resolvedSurfaceNormal(currentHit, currentDirection);
                        vec3 hitPoint = currentOrigin + currentDirection * currentHit.distance;
                        vec3 reflectionDirection = p16RoughReflectionDirection(
                            currentHit,
                            currentDirection,
                            normal,
                            roughness
                        );
                        vec3 reflectionOrigin = hitPoint
                                + normal * max(uintBitsToFloat(scene.data[59]), 0.0)
                                + reflectionDirection * max(uintBitsToFloat(scene.data[60]), 0.0);

                        vec3 baseColor = p18Surface.textured != 0u
                                ? p18Surface.albedo
                                : materialColor(currentHit.materialId);
                        vec3 f0 = p18Surface.textured != 0u
                                ? p18Surface.f0
                                : mix(vec3(0.04), baseColor, metallic);
                        // Keep conductor reflections chromatic and texture-responsive. The
                        // canonical LabPBR metal F0 remains the base, while the sampled albedo
                        // supplies controlled per-texel tint instead of producing white mirrors.
                        if (metallic > 0.001) {
                            vec3 metalTint = mix(
                                vec3(1.0),
                                clamp(baseColor, vec3(0.0), vec3(1.0)),
                                0.55 * metallic
                            );
                            f0 *= metalTint;
                            roughness = max(roughness, 0.16 * metallic);
                        }
                        float viewCosine = clamp(
                            dot(normal, -normalize(currentDirection)),
                            0.0,
                            1.0
                        );
                        vec3 fresnel = f0
                                + (vec3(1.0) - f0) * pow(1.0 - viewCosine, 5.0);
                        float roughnessEnergy = mix(
                            1.0,
                            clamp(uintBitsToFloat(scene.data[56]), 0.0, 1.0),
                            roughness * roughness
                        );
                        float materialEnergy = mix(
                            max(uintBitsToFloat(scene.data[57]), 0.0),
                            max(uintBitsToFloat(scene.data[58]), 0.0),
                            metallic
                        );
                        throughput *= fresnel
                                * roughnessEnergy
                                * materialEnergy
                                * reflectionScale;

                        P15TraceResult filteredTrace = p15TraceFiltered(
                            reflectionOrigin,
                            reflectionDirection,
                            reflectionDistance
                        );

                        if (bounce + 1u >= maxBounces) {
                            vec3 terminalRadiance;
                            if (filteredTrace.hit.hit == 0u) {
                                terminalRadiance = p13SkyRadiance(reflectionDirection)
                                        * filteredTrace.transmission;
                            } else {
                                terminalRadiance = p13EnvironmentSurfaceRadiance(
                                    filteredTrace.hit,
                                    reflectionOrigin,
                                    reflectionDirection
                                ) * filteredTrace.transmission;
                            }
                            accumulatedRadiance += throughput * terminalRadiance;
                            break;
                        }

                        HitResult nextRawHit = traceRayLimited(
                            reflectionOrigin,
                            reflectionDirection,
                            reflectionDistance
                        );
                        if (nextRawHit.hit != 0u
                                && nextRawHit.materialId == P14E_WATER_MATERIAL_ID
                                && scene.data[47] == 0u) {
                            nextRawHit = filteredTrace.hit;
                        }
                        if (nextRawHit.hit == 0u) {
                            accumulatedRadiance += throughput
                                    * p13SkyRadiance(reflectionDirection)
                                    * filteredTrace.transmission;
                            break;
                        }

                        currentHit = nextRawHit;
                        currentOrigin = reflectionOrigin;
                        currentDirection = reflectionDirection;
                    }

                    return accumulatedRadiance;
                }

                void main() {
                    uvec2 pixel = gl_GlobalInvocationID.xy;
                    uint width = scene.data[4];
                    uint height = scene.data[5];
                    if (pixel.x >= width || pixel.y >= height) return;

                    // The split pass owns the final GI composite resolve and gamma. Reflection
                    // remains optional inside this pass.
                    if (scene.data[22] != 11u) return;

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

                    uint pixelBase = scene.data[3];
                    uint pixelIndex = pixelBase + (height - 1u - pixel.y) * width + pixel.x;
                    vec3 baseRadiance = unpackRgb(scene.data[pixelIndex]);

                    vec3 finalLinearRadiance = baseRadiance;
                    if (scene.data[46] != 0u && scene.data[42] != 0u) {
                        P15TraceResult primaryTrace = p15TraceFiltered(
                            origin,
                            direction,
                            uintBitsToFloat(scene.data[7])
                        );
                        if (primaryTrace.hit.hit != 0u) {
                            finalLinearRadiance += p16ReflectionRgb(primaryTrace.hit, origin, direction)
                                    * primaryTrace.transmission;
                        }
                    }

                    vec3 resolvedLinearRadiance = p16ResolveFinalRadiance(
                        pixel,
                        width,
                        height,
                        origin,
                        direction,
                        finalLinearRadiance
                    );
                    scene.data[pixelIndex] = packRgba(
                            applyVanillaGamma(resolvedLinearRadiance),
                            255u
                    );
                }
                """;
    }

    private static String baseP12ToP15Source() {
        try {
            Field shaderField = P5StableLookupRenderer.class.getDeclaredField("SHADER");
            shaderField.setAccessible(true);
            String source = (String) shaderField.get(null);
            source = P12GiShaderPatch.apply(source);
            source = P13EndBrightnessPatch.apply(source);
            source = P14GeometryShaderPatch.apply(source);
            source = P14CommonGeometryPatch.apply(source);
            source = P14GeometryCorrectionPatch.apply(source);
            source = P13SkyOcclusionPatch.apply(source);
            source = RendererQualityShaderPatch.apply(source);
            return P18LabPbrShadingPatch.apply(source);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Failed to access P5 base shader for P16 split pass", failure);
        }
    }

    private static String span(String source, String startMarker, String endMarker, String label) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + Math.max(0, startMarker.length()));
        if (start < 0 || end < 0 || end <= start) {
            throw new IllegalStateException("P16 split shader marker missing: " + label);
        }
        return source.substring(start, end);
    }
}
