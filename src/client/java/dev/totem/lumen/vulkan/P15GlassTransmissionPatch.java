package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;

/**
 * P15A glass transmission baseline layered after P13/P14 correctness patches.
 *
 * <p>Clear/stained glass metadata is encoded in spare geometry bits. This patch filters camera,
 * sun, moon, sky, local-light and GI rays through up to eight transmissive voxels, accumulating RGB
 * tint and attenuation without growing the 32-bit voxel ABI. Refraction/Fresnel remain future work.</p>
 */
final class P15GlassTransmissionPatch {
    private P15GlassTransmissionPatch() {
    }

    static String apply(String source) {
        String skyVisibilityMarker = "float p13SkyVisibility(vec3 hitPoint, vec3 normal) {";
        if (!source.contains(skyVisibilityMarker)) {
            throw new IllegalStateException("P15 glass patch marker missing: P13 sky visibility");
        }

        String helpers = """
                struct P15TraceResult {
                    HitResult hit;
                    vec3 transmission;
                };

                bool p15GeometryIsTransmissive(uint geometryCode) {
                    uint family = geometryCode & 0xF000u;
                    if (family == 0x8000u) return true;
                    if (family == 0x4000u) {
                        uint params = geometryCode & 0x0FFFu;
                        return (params & 0x10u) != 0u;
                    }
                    return false;
                }

                vec4 p15MaterialTransmission(uint materialId, uint geometryCode) {
                    if (!p15GeometryIsTransmissive(geometryCode)) return vec4(0.0);
                    uint materialCount = scene.data[23];
                    if (materialId >= materialCount) return vec4(0.0);
                    uint base = MATERIAL_EMISSION_BASE
                            + materialId * MATERIAL_EMISSION_WORDS_PER_RECORD;
                    uint flags = scene.data[base + 11u];
                    if ((flags & 0x40u) == 0u) return vec4(0.0);

                    vec3 tint = vec3(
                        uintBitsToFloat(scene.data[base + 4u]),
                        uintBitsToFloat(scene.data[base + 5u]),
                        uintBitsToFloat(scene.data[base + 6u])
                    );
                    float opacity = clamp(uintBitsToFloat(scene.data[base + 7u]), 0.0, 1.0);
                    return vec4(clamp(tint, vec3(0.0), vec3(1.0)), 1.0 - opacity);
                }

                float p15VoxelExitDistance(vec3 point, vec3 direction, ivec3 voxel) {
                    vec3 local = clamp(point - vec3(voxel), vec3(0.0), vec3(1.0));
                    const float INF = 1.0e30;
                    float tx = direction.x > 0.000001
                            ? (1.0 - local.x) / direction.x
                            : (direction.x < -0.000001 ? (0.0 - local.x) / direction.x : INF);
                    float ty = direction.y > 0.000001
                            ? (1.0 - local.y) / direction.y
                            : (direction.y < -0.000001 ? (0.0 - local.y) / direction.y : INF);
                    float tz = direction.z > 0.000001
                            ? (1.0 - local.z) / direction.z
                            : (direction.z < -0.000001 ? (0.0 - local.z) / direction.z : INF);
                    if (tx < 0.0) tx = INF;
                    if (ty < 0.0) ty = INF;
                    if (tz < 0.0) tz = INF;
                    return min(tx, min(ty, tz));
                }

                P15TraceResult p15TraceFiltered(vec3 origin, vec3 direction, float maxDistance) {
                    P15TraceResult result;
                    result.transmission = vec3(1.0);
                    result.hit.hit = 0u;
                    result.hit.materialId = 0u;
                    result.hit.voxel = ivec3(0);
                    result.hit.normal = ivec3(0);
                    result.hit.distance = 0.0;
                    result.hit.steps = 0u;

                    float directionLength = length(direction);
                    if (directionLength < 0.000001 || maxDistance <= 0.0) return result;
                    vec3 dir = direction / directionLength;
                    vec3 cursorOrigin = origin;
                    float remaining = maxDistance;
                    float traveled = 0.0;

                    uint maxLayers = clamp(scene.data[61], 1u, 8u);
                    float exitEpsilon = max(uintBitsToFloat(scene.data[62]), 0.00001);
                    float minTransmission = clamp(uintBitsToFloat(scene.data[63]), 0.0, 1.0);
                    for (uint layer = 0u; layer < 8u; layer++) {
                        if (layer >= maxLayers) break;
                        HitResult candidate = traceRayLimited(cursorOrigin, dir, remaining);
                        if (candidate.hit == 0u) {
                            result.hit = candidate;
                            result.hit.distance += traveled;
                            return result;
                        }

                        uint geometryCode = geometryAt(candidate.voxel);
                        vec4 optical = p15MaterialTransmission(candidate.materialId, geometryCode);
                        if (optical.a <= minTransmission) {
                            candidate.distance += traveled;
                            result.hit = candidate;
                            return result;
                        }

                        result.transmission *= optical.rgb * optical.a;
                        vec3 hitPoint = cursorOrigin + dir * candidate.distance;
                        float exitDistance = p15VoxelExitDistance(
                            hitPoint + dir * 0.0005,
                            dir,
                            candidate.voxel
                        );
                        float advance = candidate.distance + exitDistance + exitEpsilon;
                        if (isnan(advance) || isinf(advance) || advance >= remaining) {
                            result.hit.hit = 0u;
                            result.hit.distance = maxDistance;
                            return result;
                        }
                        cursorOrigin += dir * advance;
                        traveled += advance;
                        remaining -= advance;
                    }

                    // More than eight transparent voxels is treated conservatively as a blocker.
                    HitResult terminal = traceRayLimited(cursorOrigin, dir, remaining);
                    if (terminal.hit != 0u) terminal.distance += traveled;
                    result.hit = terminal;
                    return result;
                }

                vec3 p15RayTransmission(vec3 origin, vec3 direction, float maxDistance) {
                    P15TraceResult filtered = p15TraceFiltered(origin, direction, maxDistance);
                    return filtered.hit.hit == 0u ? filtered.transmission : vec3(0.0);
                }

                vec3 p15SecondaryBounceRadiance(
                        HitResult hit,
                        vec3 rayOrigin,
                        vec3 rayDirection
                ) {
                    vec3 normal = resolvedSurfaceNormal(hit, rayDirection);
                    vec3 albedo = materialColor(hit.materialId);
                    vec4 emission = materialEmission(hit.materialId);
                    vec3 emitted = emission.rgb * emission.a * uintBitsToFloat(scene.data[83]);
                    uint environment = p13EnvironmentCode();

                    if (environment == 0u) {
                        vec3 hitPoint = rayOrigin + rayDirection * hit.distance;

                        // Secondary GI used to launch independent sky, sun and moon visibility
                        // rays. Reuse one conservative world-up transmission ray as the visibility
                        // proxy for all environment terms. Primary-surface lighting remains exact.
                        vec3 skyDirection = vec3(0.0, 1.0, 0.0);
                        vec3 skyOrigin = hitPoint + normal * 0.035;
                        vec3 environmentTransmission = p15RayTransmission(
                            skyOrigin,
                            skyDirection,
                            min(GI_MAX_DISTANCE, uintBitsToFloat(scene.data[7]))
                        );

                        vec3 sunDirection = p13SunDirection();
                        float nDotL = max(dot(normal, sunDirection), 0.0);
                        float sunStrength = p13SunStrength(sunDirection);

                        vec3 moonDirection = p13MoonDirection();
                        float moonNDotL = max(dot(normal, moonDirection), 0.0);
                        float moonStrength = p13MoonStrength(moonDirection);

                        vec3 skyAmbient = p13SkyRadiance(normal)
                                * environmentTransmission
                                * 0.62;
                        vec3 sun = p13SunColor(sunDirection)
                                * environmentTransmission
                                * (0.92 * nDotL * sunStrength);
                        vec3 moon = p13MoonColor()
                                * environmentTransmission
                                * (0.08 * moonNDotL * moonStrength);
                        return albedo * (skyAmbient + sun + moon) + emitted;
                    }
                    if (environment == 1u) {
                        float facing = 0.70 + 0.30 * max(normal.y, 0.0);
                        return albedo * vec3(0.145, 0.040, 0.018) * facing + emitted;
                    }
                    if (environment == 2u) {
                        float facing = 0.76 + 0.24 * max(normal.y, 0.0);
                        return albedo * vec3(0.070, 0.046, 0.115) * facing + emitted;
                    }
                    return albedo * vec3(0.060, 0.070, 0.090) + emitted;
                }

                """;
        source = source.replace(skyVisibilityMarker, helpers + skyVisibilityMarker);

        String oldSkyVisibility = """
                float p13SkyVisibility(vec3 hitPoint, vec3 normal) {
                    // A single world-up ray is a deliberately conservative baseline: sealed rooms
                    // become sky-dark, while exposed horizontal/vertical surfaces still receive sky.
                    vec3 skyDirection = vec3(0.0, 1.0, 0.0);
                    vec3 skyOrigin = hitPoint + normal * 0.035;
                    HitResult skyBlocker = traceRay(skyOrigin, skyDirection);
                    return skyBlocker.hit == 0u ? 1.0 : 0.0;
                }
                """;
        String newSkyVisibility = """
                vec3 p13SkyVisibility(vec3 hitPoint, vec3 normal) {
                    vec3 skyDirection = vec3(0.0, 1.0, 0.0);
                    vec3 skyOrigin = hitPoint + normal * 0.035;
                    return p15RayTransmission(
                        skyOrigin,
                        skyDirection,
                        uintBitsToFloat(scene.data[7])
                    );
                }
                """;
        source = replaceRequiredOnce(source, oldSkyVisibility, newSkyVisibility, "colored sky transmission");

        String oldCelestialVisibility = """
                        float visibility = 0.0;
                        if (nDotL > 0.0 && sunStrength > 0.0001) {
                            vec3 shadowOrigin = hitPoint + normal * 0.025 + sunDirection * 0.01;
                            HitResult blocker = traceRay(shadowOrigin, sunDirection);
                            visibility = blocker.hit == 0u ? 1.0 : 0.0;
                        }

                        vec3 moonDirection = p13MoonDirection();
                        float moonNDotL = max(dot(normal, moonDirection), 0.0);
                        float moonStrength = p13MoonStrength(moonDirection);
                        float moonVisibility = 0.0;
                        if (moonNDotL > 0.0 && moonStrength > 0.0001) {
                            vec3 moonShadowOrigin = hitPoint + normal * 0.025 + moonDirection * 0.01;
                            HitResult moonBlocker = traceRay(moonShadowOrigin, moonDirection);
                            moonVisibility = moonBlocker.hit == 0u ? 1.0 : 0.0;
                        }

                        vec3 skyAmbient = p13SkyRadiance(normal) * (0.62 * p13SkyVisibility(hitPoint, normal));
                        vec3 sun = p13SunColor(sunDirection)
                                * (0.92 * nDotL * visibility * sunStrength);
                        vec3 moon = p13MoonColor()
                                * (0.08 * moonNDotL * moonVisibility * moonStrength);
                """;
        String newCelestialVisibility = """
                        vec3 sunTransmission = vec3(0.0);
                        if (nDotL > 0.0 && sunStrength > 0.0001) {
                            vec3 shadowOrigin = hitPoint + normal * 0.025 + sunDirection * 0.01;
                            sunTransmission = p15RayTransmission(
                                shadowOrigin,
                                sunDirection,
                                uintBitsToFloat(scene.data[7])
                            );
                        }

                        vec3 moonDirection = p13MoonDirection();
                        float moonNDotL = max(dot(normal, moonDirection), 0.0);
                        float moonStrength = p13MoonStrength(moonDirection);
                        vec3 moonTransmission = vec3(0.0);
                        if (moonNDotL > 0.0 && moonStrength > 0.0001) {
                            vec3 moonShadowOrigin = hitPoint + normal * 0.025 + moonDirection * 0.01;
                            moonTransmission = p15RayTransmission(
                                moonShadowOrigin,
                                moonDirection,
                                uintBitsToFloat(scene.data[7])
                            );
                        }

                        vec3 skyAmbient = p13SkyRadiance(normal) * (0.62 * p13SkyVisibility(hitPoint, normal));
                        vec3 sun = p13SunColor(sunDirection)
                                * sunTransmission
                                * (0.92 * nDotL * sunStrength);
                        vec3 moon = p13MoonColor()
                                * moonTransmission
                                * (0.08 * moonNDotL * moonStrength);
                """;
        source = replaceRequiredOnce(
                source,
                oldCelestialVisibility,
                newCelestialVisibility,
                "sun/moon transmission"
        );

        String oldBounce = """
                    HitResult bounceHit = traceRayLimited(bounceOrigin, bounceDirection, GI_MAX_DISTANCE);

                    vec3 incomingRadiance;
                    if (bounceHit.hit == 0u) {
                        incomingRadiance = p13SkyRadiance(bounceDirection);
                    } else {
                        incomingRadiance = p13EnvironmentSurfaceRadiance(
                            bounceHit,
                            bounceOrigin,
                            bounceDirection
                        );
                    }
                """;
        String newBounce = """
                    P15TraceResult bounceTrace = p15TraceFiltered(
                        bounceOrigin,
                        bounceDirection,
                        GI_MAX_DISTANCE
                    );
                    HitResult bounceHit = bounceTrace.hit;

                    vec3 incomingRadiance;
                    if (bounceHit.hit == 0u) {
                        incomingRadiance = p13SkyRadiance(bounceDirection) * bounceTrace.transmission;
                    } else {
                        incomingRadiance = p15SecondaryBounceRadiance(
                            bounceHit,
                            bounceOrigin,
                            bounceDirection
                        ) * bounceTrace.transmission;
                    }
                """;
        source = replaceRequiredOnce(source, oldBounce, newBounce, "GI bounce transmission");

        source = patchLocalLightFunction(source);

        source = replaceRequiredOnce(
                source,
                "HitResult primaryHit = traceRay(origin, direction);",
                "P15TraceResult p15PrimaryTrace = p15TraceFiltered(\n"
                        + "        origin, direction, uintBitsToFloat(scene.data[7])\n"
                        + "    );\n"
                        + "    HitResult primaryHit = p15PrimaryTrace.hit;\n"
                        + "    vec3 p15PrimaryTransmission = p15PrimaryTrace.transmission;",
                "primary filtered trace"
        );

        source = replaceRequiredOnce(
                source,
                "color = packRgba(p13SkyRadiance(direction), 255u);",
                "color = packRgba(p13SkyRadiance(direction) * p15PrimaryTransmission, 255u);",
                "primary sky transmission"
        );
        source = replaceRequiredOnce(
                source,
                "color = packRgba(unpackRgb(indirectColor) * uintBitsToFloat(scene.data[84]), 255u);",
                "color = packRgba(unpackRgb(indirectColor) * uintBitsToFloat(scene.data[84])"
                        + " * p15PrimaryTransmission, 255u);",
                "indirect debug transmission"
        );
        source = patchGiCompositeCall(source);

        TotemLumenClient.LOGGER.info(
                "P15 transmission active: materialTable=true, runtimeLayers=true, runtimeAttenuation=true, panes=true, sun+moon+sky+local+GI=true, secondaryGiVisibility=singleSkyRay, refraction=false"
        );
        return source;
    }

    private static String patchLocalLightFunction(String source) {
        String localStartMarker = "uint localLightColor(";
        String localEndMarker = "uint giCurrentColor(";
        int localStart = source.indexOf(localStartMarker);
        int localEnd = source.indexOf(localEndMarker, localStart);
        if (localStart < 0 || localEnd < 0 || localEnd <= localStart) {
            throw new IllegalStateException("P15 glass patch marker missing: localLightColor span");
        }

        String local = source.substring(localStart, localEnd);
        local = replaceRequiredOnce(
                local,
                "float sampleLighting = 0.0;",
                "vec3 sampleLighting = vec3(0.0);",
                "area light sample accumulator"
        );
        local = replaceRequiredOnce(
                local,
                "float visibility = 1.0;",
                "vec3 lightTransmission = vec3(1.0);",
                "local area light visibility declaration"
        );
        local = replaceRequiredOnce(
                local,
                "HitResult blocker = traceRayLimited(shadowOrigin, lightDirection, shadowMaxDistance);",
                "lightTransmission = p15RayTransmission(shadowOrigin, lightDirection, shadowMaxDistance);",
                "local area light blocker trace"
        );
        local = replaceRequiredOnce(
                local,
                "visibility = blocker.hit == 0u ? 1.0 : 0.0;",
                "// P15 RGB transmission already includes visibility and stained-glass tint.",
                "local area light blocker result"
        );
        local = replaceRequiredOnce(
                local,
                "sampleLighting += nDotL * emitterCosine * attenuation * visibility;",
                "sampleLighting += lightTransmission * (nDotL * emitterCosine * attenuation);",
                "local area light sample accumulation"
        );
        return source.substring(0, localStart) + local + source.substring(localEnd);
    }

    private static String patchGiCompositeCall(String source) {
        String marker = "color = giCompositeFromIndirect(";
        int start = source.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("P15 glass patch marker missing: GI composite call");
        }
        int end = source.indexOf(");", start);
        if (end < 0) {
            throw new IllegalStateException("P15 glass patch marker missing: GI composite call end");
        }
        end += 2;
        String call = source.substring(start, end);
        if (!call.contains("primaryHit") || !call.contains("indirectColor")) {
            throw new IllegalStateException("P15 glass patch GI composite call did not match expected arguments");
        }
        String replacement = "color = packRgba(unpackRgb(" + call.substring("color = ".length(), call.length() - 1)
                + ") * p15PrimaryTransmission, 255u);";
        return source.substring(0, start) + replacement + source.substring(end);
    }

    private static String replaceRequiredOnce(String source, String oldText, String newText, String label) {
        int first = source.indexOf(oldText);
        if (first < 0) {
            throw new IllegalStateException("P15 glass patch marker missing: " + label);
        }
        int second = source.indexOf(oldText, first + oldText.length());
        if (second >= 0) {
            throw new IllegalStateException("P15 glass patch marker is ambiguous: " + label);
        }
        return source.substring(0, first) + newText + source.substring(first + oldText.length());
    }
}
