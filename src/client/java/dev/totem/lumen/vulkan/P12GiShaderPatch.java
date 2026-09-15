package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;

/**
 * P12 correctness patch applied to the monolithic validation shader before shaderc compilation.
 *
 * <p>The initial alpha.19 path accumulated the complete GI composite in the same temporal history.
 * That made sparse one-sample indirect estimates dominate the entire image in dark scenes. This
 * patch keeps the proven P9/P11 direct/local base outside GI history and accumulates only indirect
 * radiance. It uses two bounce samples per frame, progressive per-surface history and a conservative
 * firefly clamp. The transform is intentionally strict and fails fast if the expected P12 source
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
            throw new IllegalStateException("P12 GI shader patch marker missing: currentTemporalSampleColor");
        }

        String helpers = """
                vec3 giIndirectCurrentRgb(
                        HitResult hit,
                        vec3 primaryOrigin,
                        vec3 primaryDirection,
                        uvec2 pixel,
                        uint sampleIndex
                ) {
                    vec3 sum = vec3(0.0);
                    for (uint sampleOffset = 0u; sampleOffset < 2u; sampleOffset++) {
                        sum += oneBounceIndirectRgb(
                            hit,
                            primaryOrigin,
                            primaryDirection,
                            pixel,
                            sampleIndex * 2u + sampleOffset
                        );
                    }
                    vec3 indirect = sum * 0.5;
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
                        giIndirectCurrentRgb(hit, primaryOrigin, primaryDirection, pixel, scene.data[41]),
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
                    uint previousSamples = clamp(historySampleCount(historyBase), 1u, GI_HISTORY_MAX_SAMPLES);
                    outputSampleCount = min(previousSamples + 1u, GI_HISTORY_MAX_SAMPLES);
                    float historyWeight = float(previousSamples) / float(previousSamples + 1u);
                    return packRgba(mix(currentRgb, historyRgb, historyWeight), 255u);
                }

                uint giCompositeFromIndirect(
                        HitResult hit,
                        vec3 primaryOrigin,
                        vec3 primaryDirection,
                        uint indirectColor
                ) {
                    vec3 direct = unpackRgb(softShadowColor(hit, primaryOrigin, primaryDirection));
                    vec3 localWithBase = unpackRgb(localLightColor(hit, primaryOrigin, primaryDirection, true));
                    vec4 emission = materialEmission(hit.materialId);
                    vec3 emitted = emission.rgb * emission.a * 1.6;
                    vec3 localBase = materialColor(hit.materialId) * 0.045 + emitted;
                    vec3 localContribution = max(localWithBase - localBase, vec3(0.0));
                    return packRgba(direct + localContribution + unpackRgb(indirectColor), 255u);
                }

                """;
        source = source.replace(marker, helpers + marker);

        String branchStart = "    if (mode == 8u || mode == 9u || mode == 10u || mode == 11u) {";
        String branchEnd = "    uint pixelBase = scene.data[3];";
        int startIndex = source.indexOf(branchStart);
        int endIndex = source.indexOf(branchEnd, startIndex);
        if (startIndex < 0 || endIndex < 0 || endIndex <= startIndex) {
            throw new IllegalStateException("P12 GI shader patch marker missing: temporal main branch");
        }

        String newMainBranch = """
                if (mode == 10u || mode == 11u) {
                    uint giHistorySamples = 1u;
                    if (primaryHit.hit == 0u) {
                        color = packRgba(vec3(0.03, 0.05, 0.08), 255u);
                        writeHistory(pixel, width, primaryHit, packRgba(vec3(0.0), 255u), giHistorySamples);
                    } else {
                        uint indirectColor = giTemporalIndirectColor(
                            primaryHit, origin, direction, pixel, width, height, giHistorySamples
                        );
                        writeHistory(pixel, width, primaryHit, indirectColor, giHistorySamples);
                        if (mode == 10u) {
                            color = packRgba(unpackRgb(indirectColor) * 1.6, 255u);
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
                "P12 GI shader correctness patch active: samplesPerFrame={}, progressiveHistoryMaxSamples={}, peakClamp={}, history=indirect-only",
                SAMPLES_PER_FRAME,
                HISTORY_MAX_SAMPLES,
                SAMPLE_PEAK_CLAMP
        );
        return source;
    }
}
