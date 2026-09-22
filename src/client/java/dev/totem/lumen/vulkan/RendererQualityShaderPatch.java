package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;

/**
 * Runtime quality controls layered after P15 so colored glass transmission remains authoritative.
 */
final class RendererQualityShaderPatch {
    private RendererQualityShaderPatch() {
    }

    static String apply(String source) {
        String environmentMarker = "vec3 p13EnvironmentSurfaceRadiance(\n";
        if (!source.contains(environmentMarker)) {
            throw new IllegalStateException("Runtime quality patch marker missing: P13 environment surface");
        }

        String helper = """
                vec3 tlRuntimeDirectionalTransmission(
                        vec3 hitPoint,
                        vec3 normal,
                        vec3 lightDirection
                ) {
                    uint shadowSamples = clamp(scene.data[45], 1u, 4u);
                    vec3 helperAxis = abs(lightDirection.y) < 0.95
                            ? vec3(0.0, 1.0, 0.0)
                            : vec3(1.0, 0.0, 0.0);
                    vec3 tangent = normalize(cross(helperAxis, lightDirection));
                    vec3 bitangent = normalize(cross(lightDirection, tangent));
                    vec3 transmission = vec3(0.0);
                    float validSamples = 0.0;

                    for (uint sampleIndex = 0u; sampleIndex < 4u; sampleIndex++) {
                        if (sampleIndex >= shadowSamples) break;
                        vec2 offset = SOFT_SHADOW_OFFSETS[int(sampleIndex)];
                        vec3 sampleDirection = normalize(
                            lightDirection
                            + tangent * (offset.x * SOFT_SHADOW_ANGULAR_RADIUS)
                            + bitangent * (offset.y * SOFT_SHADOW_ANGULAR_RADIUS)
                        );
                        if (dot(normal, sampleDirection) <= 0.0) continue;

                        validSamples += 1.0;
                        vec3 shadowOrigin = hitPoint + normal * 0.025 + sampleDirection * 0.01;
                        transmission += p15RayTransmission(
                            shadowOrigin,
                            sampleDirection,
                            uintBitsToFloat(scene.data[7])
                        );
                    }

                    return validSamples > 0.0
                            ? transmission / validSamples
                            : vec3(0.0);
                }

                """;
        source = source.replace(environmentMarker, helper + environmentMarker);

        String oldSun = """
                        vec3 sunTransmission = vec3(0.0);
                        if (nDotL > 0.0 && sunStrength > 0.0001) {
                            vec3 shadowOrigin = hitPoint + normal * 0.025 + sunDirection * 0.01;
                            sunTransmission = p15RayTransmission(
                                shadowOrigin,
                                sunDirection,
                                uintBitsToFloat(scene.data[7])
                            );
                        }
                """;
        String newSun = """
                        vec3 sunTransmission = vec3(0.0);
                        if (nDotL > 0.0 && sunStrength > 0.0001) {
                            sunTransmission = tlRuntimeDirectionalTransmission(
                                hitPoint,
                                normal,
                                sunDirection
                            );
                        }
                """;
        source = replaceRequiredOnce(source, oldSun, newSun, "sun quality");

        String oldMoon = """
                        vec3 moonTransmission = vec3(0.0);
                        if (moonNDotL > 0.0 && moonStrength > 0.0001) {
                            vec3 moonShadowOrigin = hitPoint + normal * 0.025 + moonDirection * 0.01;
                            moonTransmission = p15RayTransmission(
                                moonShadowOrigin,
                                moonDirection,
                                uintBitsToFloat(scene.data[7])
                            );
                        }
                """;
        String newMoon = """
                        vec3 moonTransmission = vec3(0.0);
                        if (moonNDotL > 0.0 && moonStrength > 0.0001) {
                            moonTransmission = tlRuntimeDirectionalTransmission(
                                hitPoint,
                                normal,
                                moonDirection
                            );
                        }
                """;
        source = replaceRequiredOnce(source, oldMoon, newMoon, "moon quality");

        TotemLumenClient.LOGGER.info(
                "Runtime renderer quality shader patch active: giSamples=1/2/4, shadowSamples=1/2/4 directional+localArea, "
                        + "temporal=off/fast/stable, denoiseRadius=0/1/2"
        );
        return source;
    }

    private static String replaceRequiredOnce(
            String source,
            String oldText,
            String newText,
            String label
    ) {
        int first = source.indexOf(oldText);
        if (first < 0) {
            throw new IllegalStateException("Runtime quality patch marker missing: " + label);
        }
        int second = source.indexOf(oldText, first + oldText.length());
        if (second >= 0) {
            throw new IllegalStateException("Runtime quality patch marker is ambiguous: " + label);
        }
        return source.substring(0, first) + newText + source.substring(first + oldText.length());
    }
}
