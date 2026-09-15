package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;

/**
 * P16 reflection/roughness baseline layered after P15 filtered transmission.
 *
 * <p>One deterministic secondary reflection ray is evaluated for the GI composite. Full-cube
 * roughness/metallic response is decoded from the P16 SURFACE_CUBE geometry family; existing P15
 * glass metadata remains transmissive and is respected by the reflection ray. This intentionally
 * stops at one reflection bounce and does not introduce recursive specular transport.</p>
 */
final class P16ReflectionRoughnessPatch {
    static final float REFLECTION_MAX_DISTANCE = 64.0f;

    private P16ReflectionRoughnessPatch() {
    }

    static String apply(String source) {
        String helperMarker = "vec3 p13OneBounceIndirectRgb(\n";
        if (!source.contains(helperMarker)) {
            throw new IllegalStateException("P16 reflection patch marker missing: P13 GI helper");
        }

        String helpers = """
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
                    vec2 surface = p16SurfaceProperties(primaryHit);
                    float roughness = surface.x;
                    float metallic = surface.y;
                    vec3 normal = resolvedSurfaceNormal(primaryHit, primaryDirection);
                    vec3 hitPoint = primaryOrigin + primaryDirection * primaryHit.distance;
                    vec3 reflectionDirection = p16RoughReflectionDirection(
                        primaryHit,
                        primaryDirection,
                        normal,
                        roughness
                    );
                    vec3 reflectionOrigin = hitPoint + normal * 0.035 + reflectionDirection * 0.01;
                    float reflectionDistance = min(
                        uintBitsToFloat(scene.data[7]),
                        64.0
                    );
                    P15TraceResult reflectionTrace = p15TraceFiltered(
                        reflectionOrigin,
                        reflectionDirection,
                        reflectionDistance
                    );

                    vec3 incomingRadiance;
                    if (reflectionTrace.hit.hit == 0u) {
                        incomingRadiance = p13SkyRadiance(reflectionDirection)
                                * reflectionTrace.transmission;
                    } else {
                        incomingRadiance = p13EnvironmentSurfaceRadiance(
                            reflectionTrace.hit,
                            reflectionOrigin,
                            reflectionDirection
                        ) * reflectionTrace.transmission;
                    }

                    vec3 baseColor = materialColor(primaryHit.materialId);
                    vec3 f0 = mix(vec3(0.04), baseColor, metallic);
                    float viewCosine = clamp(
                        dot(normal, -normalize(primaryDirection)),
                        0.0,
                        1.0
                    );
                    vec3 fresnel = f0
                            + (vec3(1.0) - f0) * pow(1.0 - viewCosine, 5.0);
                    float roughnessEnergy = mix(1.0, 0.18, roughness * roughness);
                    float materialEnergy = mix(0.85, 1.0, metallic);
                    return incomingRadiance * fresnel * roughnessEnergy * materialEnergy;
                }

                """;
        source = source.replace(helperMarker, helpers + helperMarker);

        String oldComposite =
                "return packRgba(environmentDirect + localContribution + unpackRgb(indirectColor), 255u);";
        String newComposite = """
                vec3 p16BaseRadiance = environmentDirect
                        + localContribution
                        + unpackRgb(indirectColor);
                vec3 p16ReflectedRadiance = p16ReflectionRgb(
                    hit,
                    primaryOrigin,
                    primaryDirection
                );
                return packRgba(p16BaseRadiance + p16ReflectedRadiance, 255u);
                """.strip();
        source = replaceRequiredOnce(source, oldComposite, newComposite, "GI composite reflection");

        TotemLumenClient.LOGGER.info(
                "P16 reflection/roughness active: secondaryRays=1, maxDistance={}, roughness=4bit, metallic=4bit, fresnel=Schlick, recursive=false",
                REFLECTION_MAX_DISTANCE
        );
        return source;
    }

    private static String replaceRequiredOnce(String source, String oldText, String newText, String label) {
        int first = source.indexOf(oldText);
        if (first < 0) {
            throw new IllegalStateException("P16 reflection patch marker missing: " + label);
        }
        int second = source.indexOf(oldText, first + oldText.length());
        if (second >= 0) {
            throw new IllegalStateException("P16 reflection patch marker is ambiguous: " + label);
        }
        return source.substring(0, first) + newText + source.substring(first + oldText.length());
    }
}
