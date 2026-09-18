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

                const uint LOOKUP_BASE = 64u;
                const uint LOOKUP_CAPACITY = 128u;
                const uint LOOKUP_MASK = 127u;
                const uint LOOKUP_WORDS_PER_BUCKET = 4u;
                const uint VOXEL_BASE = 576u;
                const uint VOXELS_PER_SECTION = 4096u;
                const uint MATERIAL_EMISSION_BASE = 265344u;
                const uint MATERIAL_EMISSION_WORDS_PER_RECORD = 4u;

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

                vec2 p16SurfaceProperties(HitResult hit) {
                    uint geometryCode = geometryAt(hit.voxel);
                    uint family = geometryCode & 0xF000u;
                    uint params = geometryCode & 0x0FFFu;

                    if (family == 0x9000u) {
                        float roughness = float(params & 0xFu) / 15.0;
                        float metallic = float((params >> 4u) & 0xFu) / 15.0;
                        return vec2(roughness, metallic);
                    }
                    if (family == 0x8000u) {
                        return vec2(0.05, 0.0);
                    }
                    if (family == 0x4000u && (params & 0x10u) != 0u) {
                        return vec2(0.07, 0.0);
                    }
                    return vec2(0.80, 0.0);
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
                    float spread = roughness * roughness * 0.75;
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

                        vec2 surface = p16SurfaceProperties(currentHit);
                        float roughness = surface.x;
                        float metallic = surface.y;
                        vec3 normal = resolvedSurfaceNormal(currentHit, currentDirection);
                        vec3 hitPoint = currentOrigin + currentDirection * currentHit.distance;
                        vec3 reflectionDirection = p16RoughReflectionDirection(
                            currentHit,
                            currentDirection,
                            normal,
                            roughness
                        );
                        vec3 reflectionOrigin = hitPoint + normal * 0.035 + reflectionDirection * 0.01;

                        vec3 baseColor = materialColor(currentHit.materialId);
                        vec3 f0 = mix(vec3(0.04), baseColor, metallic);
                        float viewCosine = clamp(
                            dot(normal, -normalize(currentDirection)),
                            0.0,
                            1.0
                        );
                        vec3 fresnel = f0
                                + (vec3(1.0) - f0) * pow(1.0 - viewCosine, 5.0);
                        float roughnessEnergy = mix(1.0, 0.18, roughness * roughness);
                        float materialEnergy = mix(0.85, 1.0, metallic);
                        throughput *= fresnel * roughnessEnergy * materialEnergy;

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
                        if (nextRawHit.hit == 0u) {
                            accumulatedRadiance += throughput
                                    * p13SkyRadiance(reflectionDirection);
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

                    // P16 historically affected only the final GI composite debug mode.
                    if (scene.data[22] != 11u) return;
                    if (scene.data[42] == 0u) return;

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

                    P15TraceResult primaryTrace = p15TraceFiltered(
                        origin,
                        direction,
                        uintBitsToFloat(scene.data[7])
                    );
                    if (primaryTrace.hit.hit == 0u) return;

                    vec3 reflected = p16ReflectionRgb(primaryTrace.hit, origin, direction)
                            * primaryTrace.transmission;
                    uint pixelBase = scene.data[3];
                    uint pixelIndex = pixelBase + pixel.y * width + pixel.x;
                    vec3 baseRadiance = unpackRgb(scene.data[pixelIndex]);
                    scene.data[pixelIndex] = packRgba(baseRadiance + reflected, 255u);
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
            return P13SkyOcclusionPatch.apply(source);
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
