package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;

/**
 * P15A glass transmission baseline layered after P13/P14 correctness patches.
 *
 * <p>Clear/stained glass metadata is encoded in spare geometry bits. This patch filters camera,
 * sun, sky, local-light and GI rays through up to eight transmissive voxels, accumulating RGB tint
 * and attenuation without growing the 32-bit voxel ABI. Refraction/Fresnel remain future work.</p>
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

                vec3 p15TintRgb(uint tint) {
                    if (tint == 1u) return vec3(1.00, 0.98, 0.95);
                    if (tint == 2u) return vec3(1.00, 0.58, 0.28);
                    if (tint == 3u) return vec3(0.90, 0.42, 0.92);
                    if (tint == 4u) return vec3(0.48, 0.76, 1.00);
                    if (tint == 5u) return vec3(1.00, 0.88, 0.32);
                    if (tint == 6u) return vec3(0.58, 0.92, 0.34);
                    if (tint == 7u) return vec3(1.00, 0.58, 0.74);
                    if (tint == 8u) return vec3(0.48, 0.50, 0.52);
                    if (tint == 9u) return vec3(0.76, 0.78, 0.80);
                    if (tint == 10u) return vec3(0.34, 0.78, 0.82);
                    if (tint == 11u) return vec3(0.67, 0.42, 0.86);
                    if (tint == 12u) return vec3(0.38, 0.48, 0.92);
                    if (tint == 13u) return vec3(0.62, 0.44, 0.28);
                    if (tint == 14u) return vec3(0.40, 0.70, 0.34);
                    if (tint == 15u) return vec3(0.96, 0.34, 0.30);
                    if (tint == 16u) return vec3(0.24, 0.25, 0.29);
                    return vec3(1.0);
                }

                vec4 p15GeometryTransmission(uint geometryCode) {
                    uint family = geometryCode & 0xF000u;
                    if (family == 0x8000u) {
                        uint tint = geometryCode & 0x1Fu;
                        float scalar = tint == 0u ? 0.95 : 0.88;
                        return vec4(p15TintRgb(tint), scalar);
                    }
                    if (family == 0x4000u) {
                        uint params = geometryCode & 0x0FFFu;
                        if ((params & 0x10u) != 0u) {
                            uint tint = (params >> 5u) & 0x1Fu;
                            float scalar = tint == 0u ? 0.95 : 0.88;
                            return vec4(p15TintRgb(tint), scalar);
                        }
                    }
                    return vec4(0.0);
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

                    for (uint layer = 0u; layer < 8u; layer++) {
                        HitResult candidate = traceRayLimited(cursorOrigin, dir, remaining);
                        if (candidate.hit == 0u) {
                            result.hit = candidate;
                            result.hit.distance += traveled;
                            return result;
                        }

                        uint geometryCode = geometryAt(candidate.voxel);
                        vec4 optical = p15GeometryTransmission(geometryCode);
                        if (optical.a <= 0.0001) {
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
                        float advance = candidate.distance + exitDistance + 0.002;
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

        String oldSunVisibility = """
                        float visibility = 0.0;
                        if (nDotL > 0.0 && sunStrength > 0.0001) {
                            vec3 shadowOrigin = hitPoint + normal * 0.025 + sunDirection * 0.01;
                            HitResult blocker = traceRay(shadowOrigin, sunDirection);
                            visibility = blocker.hit == 0u ? 1.0 : 0.0;
                        }

                        vec3 skyAmbient = p13SkyRadiance(normal) * (0.62 * p13SkyVisibility(hitPoint, normal));
                        vec3 sun = p13SunColor(sunDirection)
                                * (0.92 * nDotL * visibility * sunStrength);
                """;
        String newSunVisibility = """
                        vec3 sunTransmission = vec3(0.0);
                        if (nDotL > 0.0 && sunStrength > 0.0001) {
                            vec3 shadowOrigin = hitPoint + normal * 0.025 + sunDirection * 0.01;
                            sunTransmission = p15RayTransmission(
                                shadowOrigin,
                                sunDirection,
                                uintBitsToFloat(scene.data[7])
                            );
                        }

                        vec3 skyAmbient = p13SkyRadiance(normal) * (0.62 * p13SkyVisibility(hitPoint, normal));
                        vec3 sun = p13SunColor(sunDirection)
                                * sunTransmission
                                * (0.92 * nDotL * sunStrength);
                """;
        source = replaceRequiredOnce(source, oldSunVisibility, newSunVisibility, "sun transmission");

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
                        incomingRadiance = p13EnvironmentSurfaceRadiance(
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
                "color = packRgba(unpackRgb(indirectColor) * 1.6, 255u);",
                "color = packRgba(unpackRgb(indirectColor) * 1.6 * p15PrimaryTransmission, 255u);",
                "indirect debug transmission"
        );
        source = patchGiCompositeCall(source);

        TotemLumenClient.LOGGER.info(
                "P15 glass transmission active: clearGlass=true, stainedGlassRgb=true, panes=true, maxTransparentLayers=8, sun+sky+local+GI=true, refraction=false"
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
                "float visibility = 1.0;",
                "vec3 lightTransmission = vec3(1.0);",
                "local light visibility declaration"
        );
        local = replaceRequiredOnce(
                local,
                "HitResult blocker = traceRayLimited(shadowOrigin, lightDirection, shadowMaxDistance);",
                "lightTransmission = p15RayTransmission(shadowOrigin, lightDirection, shadowMaxDistance);",
                "local light blocker trace"
        );
        local = replaceRequiredOnce(
                local,
                "visibility = blocker.hit == 0u ? 1.0 : 0.0;",
                "// P15 RGB transmission already includes visibility and stained-glass tint.",
                "local light blocker result"
        );
        local = replaceRequiredOnce(
                local,
                "lighting += lightColor * (2.4 * nDotL * attenuation * intensity * visibility);",
                "lighting += lightColor * lightTransmission * (2.4 * nDotL * attenuation * intensity);",
                "local light accumulation"
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
