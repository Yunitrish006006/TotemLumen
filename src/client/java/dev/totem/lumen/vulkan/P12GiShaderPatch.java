package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;

/**
 * P12/P13 correctness patch applied to the monolithic validation shader before shaderc compilation.
 *
 * <p>P12 keeps direct/local light outside the stochastic GI history and progressively accumulates
 * only indirect radiance. P13 layers dimension-aware environment lighting on that stable path:
 * Overworld gets a time-of-day sun/moon/sky model, Nether and End get distinct ambient
 * environments, primary sky misses expose environment radiance, and GI bounce misses can sample
 * the environment. The transform is intentionally strict and fails fast if the expected source
 * markers change.</p>
 */
final class P12GiShaderPatch {
    static final int SAMPLES_PER_FRAME = 2;
    static final int HISTORY_MAX_SAMPLES = 64;
    static final float SAMPLE_PEAK_CLAMP = 0.90f;

    private P12GiShaderPatch() {
    }

    static String apply(String source) {
        String marker = "uint currentTemporalSampleColor(";
        if (!source.contains(marker)) {
            throw new IllegalStateException("P12/P13 shader patch marker missing: currentTemporalSampleColor");
        }

        String helpers = """
                uint p13EnvironmentCode() {
                    return scene.data[41] >> 30u;
                }

                float p13DayPhase() {
                    return float((scene.data[41] >> 19u) & 0x07FFu) / 2048.0;
                }

                uint p13MoonPhase() {
                    return (scene.data[41] >> 16u) & 0x7u;
                }

                uint p13FrameSeed() {
                    return scene.data[41] & 0xFFFFu;
                }

                vec3 p13SunDirection() {
                    float angle = p13DayPhase() * 6.28318530718;
                    return normalize(vec3(cos(angle) * 0.92, sin(angle), 0.32));
                }

                float p13SunStrength(vec3 sunDirection) {
                    return smoothstep(0.0, 0.14, sunDirection.y);
                }

                vec3 p13SunColor(vec3 sunDirection) {
                    float elevation = clamp(sunDirection.y, 0.0, 1.0);
                    return mix(
                        vec3(1.0, 0.47, 0.20),
                        vec3(1.0, 0.95, 0.82),
                        smoothstep(0.04, 0.45, elevation)
                    );
                }

                vec3 p13MoonDirection() {
                    return -p13SunDirection();
                }

                float p13MoonPhaseBrightness() {
                    uint phase = p13MoonPhase();
                    uint distanceFromFull = min(phase, 8u - phase);
                    return 1.0 - float(distanceFromFull) * 0.25;
                }

                float p13MoonHorizonStrength(vec3 moonDirection) {
                    return smoothstep(0.0, 0.12, moonDirection.y);
                }

                float p13MoonStrength(vec3 moonDirection) {
                    return p13MoonHorizonStrength(moonDirection) * p13MoonPhaseBrightness();
                }

                vec3 p13MoonColor() {
                    return vec3(0.58, 0.70, 1.0);
                }

                float p13MoonPhaseMask(vec3 direction, vec3 moonDirection) {
                    uint phase = p13MoonPhase();
                    if (phase == 0u) return 1.0;
                    if (phase == 4u) return 0.0;

                    vec3 dir = normalize(direction);
                    vec3 moonRight = normalize(cross(vec3(0.0, 0.0, 1.0), moonDirection));
                    vec3 moonUp = normalize(cross(moonDirection, moonRight));
                    const float diskEdgeDot = 0.9955;
                    float diskRadius = sqrt(max(1.0 - diskEdgeDot * diskEdgeDot, 0.000001));
                    float x = clamp(dot(dir, moonRight) / diskRadius, -1.0, 1.0);
                    float y = clamp(dot(dir, moonUp) / diskRadius, -1.0, 1.0);
                    float radiusSquared = x * x + y * y;
                    if (radiusSquared > 1.0) return 0.0;

                    float sphereZ = sqrt(max(1.0 - radiusSquared, 0.0));
                    float phaseAngle = float(phase) * 0.78539816339;
                    float illumination = sphereZ * cos(phaseAngle) - x * sin(phaseAngle);
                    return smoothstep(-0.025, 0.025, illumination);
                }

                float p13MoonDisk(vec3 direction, vec3 moonDirection) {
                    float moonDot = max(dot(normalize(direction), moonDirection), 0.0);
                    float disk = smoothstep(0.9955, 0.9985, moonDot);
                    return disk
                            * p13MoonPhaseMask(direction, moonDirection)
                            * p13MoonHorizonStrength(moonDirection);
                }

                vec3 p13SkyRadiance(vec3 direction) {
                    uint environment = p13EnvironmentCode();
                    vec3 dir = normalize(direction);
                    float up = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);

                    if (environment == 0u) {
                        vec3 sunDirection = p13SunDirection();
                        vec3 moonDirection = p13MoonDirection();
                        float daylight = smoothstep(-0.18, 0.08, sunDirection.y);
                        vec3 dayHorizon = vec3(0.38, 0.46, 0.58);
                        vec3 dayZenith = vec3(0.09, 0.30, 0.68);
                        vec3 daySky = mix(dayHorizon, dayZenith, pow(up, 0.70));
                        vec3 nightHorizon = vec3(0.010, 0.014, 0.028);
                        vec3 nightZenith = vec3(0.004, 0.008, 0.026);
                        vec3 nightSky = mix(nightHorizon, nightZenith, up) * 1.12;
                        vec3 sky = mix(nightSky, daySky, daylight) * 0.58;

                        float sunDot = max(dot(dir, sunDirection), 0.0);
                        float sunDisk = pow(sunDot, 640.0) * p13SunStrength(sunDirection) * 2.0;
                        float moonDisk = p13MoonDisk(dir, moonDirection);
                        float moonGlow = pow(max(dot(dir, moonDirection), 0.0), 96.0)
                                * p13MoonStrength(moonDirection) * 0.045;
                        return sky
                                + p13SunColor(sunDirection) * sunDisk
                                + p13MoonColor() * (moonDisk * 0.72 + moonGlow);
                    }
                    if (environment == 1u) {
                        return vec3(0.105, 0.024, 0.010) * (0.82 + 0.18 * up);
                    }
                    if (environment == 2u) {
                        return vec3(0.040, 0.024, 0.074) * (0.88 + 0.12 * up);
                    }
                    return vec3(0.032, 0.042, 0.060) * (0.82 + 0.18 * up);
                }

                vec3 p13EnvironmentSurfaceRadiance(
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
                        vec3 sunDirection = p13SunDirection();
                        float nDotL = max(dot(normal, sunDirection), 0.0);
                        float sunStrength = p13SunStrength(sunDirection);
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

                        vec3 skyAmbient = p13SkyRadiance(normal) * 0.62;
                        vec3 sun = p13SunColor(sunDirection)
                                * (0.92 * nDotL * visibility * sunStrength);
                        vec3 moon = p13MoonColor()
                                * (0.20 * moonNDotL * moonVisibility * moonStrength);
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

                vec3 p13OneBounceIndirectRgb(
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
                    return materialColor(primaryHit.materialId) * incomingRadiance * GI_STRENGTH;
                }

                vec3 giIndirectCurrentRgb(
                        HitResult hit,
                        vec3 primaryOrigin,
                        vec3 primaryDirection,
                        uvec2 pixel,
                        uint sampleIndex
                ) {
                    uint giSamples = clamp(scene.data[44], 1u, 4u);
                    vec3 sum = vec3(0.0);
                    for (uint sampleOffset = 0u; sampleOffset < 4u; sampleOffset++) {
                        if (sampleOffset >= giSamples) break;
                        sum += p13OneBounceIndirectRgb(
                            hit,
                            primaryOrigin,
                            primaryDirection,
                            pixel,
                            sampleIndex * giSamples + sampleOffset
                        );
                    }
                    vec3 indirect = sum / float(giSamples);
                    float peak = max(indirect.r, max(indirect.g, indirect.b));
                    if (peak > 0.90) {
                        indirect *= 0.90 / peak;
                    }
                    return indirect;
                }

                uint giTemporalIndirectColor(
                        HitResult hit,
                        vec3 primaryOrigin,
                        vec3 primaryDirection,
                        uvec2 pixel,
                        uint width,
                        uint height,
                        out uint outputSampleCount
                ) {
                    uint currentColor = packRgba(
                        giIndirectCurrentRgb(hit, primaryOrigin, primaryDirection, pixel, p13FrameSeed()),
                        255u
                    );
                    outputSampleCount = 1u;
                    if (scene.data[26] == 0u) return currentColor;

                    vec3 hitPoint = primaryOrigin + primaryDirection * hit.distance;
                    uvec2 previousPixel;
                    if (!reprojectToPrevious(hitPoint, width, height, previousPixel)) return currentColor;

                    uint previousLinear = previousPixel.y * width + previousPixel.x;
                    uint historyBase = scene.data[24] + previousLinear * HISTORY_RECORD_WORDS;
                    if (!historyMatches(historyBase, hit)) return currentColor;

                    vec3 currentRgb = unpackRgb(currentColor);
                    vec3 historyRgb = spatialHistoryRgb(
                        previousPixel,
                        width,
                        height,
                        hit,
                        unpackRgb(scene.data[historyBase])
                    );
                    uint historyLimit = max(scene.data[48], 1u);
                    uint previousSamples = clamp(historySampleCount(historyBase), 1u, historyLimit);
                    outputSampleCount = min(previousSamples + 1u, historyLimit);
                    float historyWeight = float(previousSamples) / float(previousSamples + 1u);
                    return packRgba(mix(currentRgb, historyRgb, historyWeight), 255u);
                }

                uint giCompositeFromIndirect(
                        HitResult hit,
                        vec3 primaryOrigin,
                        vec3 primaryDirection,
                        uint indirectColor
                ) {
                    vec3 environmentDirect = p13EnvironmentSurfaceRadiance(
                        hit,
                        primaryOrigin,
                        primaryDirection
                    );
                    vec3 localWithBase = unpackRgb(localLightColor(hit, primaryOrigin, primaryDirection, true));
                    vec4 emission = materialEmission(hit.materialId);
                    vec3 emitted = emission.rgb * emission.a * uintBitsToFloat(scene.data[83]);
                    vec3 localBase = materialColor(hit.materialId) * uintBitsToFloat(scene.data[82]) + emitted;
                    vec3 localContribution = max(localWithBase - localBase, vec3(0.0));
                    return packRgba(environmentDirect + localContribution + unpackRgb(indirectColor), 255u);
                }

                """;
        source = source.replace(marker, helpers + marker);

        String branchStart = "    if (mode == 8u || mode == 9u || mode == 10u || mode == 11u) {";
        String branchEnd = "    uint pixelBase = scene.data[3];";
        int startIndex = source.indexOf(branchStart);
        int endIndex = source.indexOf(branchEnd, startIndex);
        if (startIndex < 0 || endIndex < 0 || endIndex <= startIndex) {
            throw new IllegalStateException("P12/P13 shader patch marker missing: temporal main branch");
        }

        String newMainBranch = """
                if (mode == 10u || mode == 11u) {
                    uint giHistorySamples = 1u;
                    if (primaryHit.hit == 0u) {
                        if (mode == 10u) {
                            color = packRgba(vec3(0.0), 255u);
                        } else {
                            color = packRgba(p13SkyRadiance(direction), 255u);
                        }
                        writeHistory(pixel, width, primaryHit, packRgba(vec3(0.0), 255u), giHistorySamples);
                    } else {
                        uint indirectColor = giTemporalIndirectColor(
                            primaryHit, origin, direction, pixel, width, height, giHistorySamples
                        );
                        writeHistory(pixel, width, primaryHit, indirectColor, giHistorySamples);
                        if (mode == 10u) {
                            color = packRgba(unpackRgb(indirectColor) * uintBitsToFloat(scene.data[84]), 255u);
                        } else {
                            color = giCompositeFromIndirect(
                                primaryHit, origin, direction, indirectColor
                            );
                        }
                    }
                } else if (mode == 8u || mode == 9u) {
                    uint directHistorySamples = 1u;
                    if (primaryHit.hit == 0u) {
                        color = packRgba(vec3(0.03, 0.05, 0.08), 255u);
                    } else {
                        color = temporalHistoryColor(
                            primaryHit, origin, direction, pixel, width, height, directHistorySamples
                        );
                    }
                    writeHistory(pixel, width, primaryHit, color, directHistorySamples);
                } else {
                    color = debugColor(primaryHit, origin, direction);
                }
                """.indent(4);

        source = source.substring(0, startIndex) + newMainBranch + source.substring(endIndex);

        TotemLumenClient.LOGGER.info(
                "P12 GI shader correctness patch active: samplesPerFrame=runtime(1/2/4), progressiveHistory=runtime(off/16/64), peakClamp={}, history=indirect-only",
                SAMPLE_PEAK_CLAMP
        );
        TotemLumenClient.LOGGER.info(
                "P13 environment lighting patch active: dynamicOverworldSun=true, dynamicOverworldMoon=true, moonPhase=true, moonPhaseSteps=8, skyMissRadiance=true, dimensions=overworld+nether+end+fallback"
        );
        TotemLumenClient.LOGGER.info(
                "P13 celestial environment lighting active: sun=true, moon=true, moonSurfaceEnergy=0.20, stellarAmbient=starfield-patched"
        );
        return source;
    }
}
