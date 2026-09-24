package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;

/** Adds a deterministic procedural Overworld star field to the shared P13 sky radiance path. */
final class P13StarfieldPatch {
    private P13StarfieldPatch() {
    }

    static String apply(String source) {
        String skyMarker = "vec3 p13SkyRadiance(vec3 direction) {";
        if (!source.contains(skyMarker)) {
            throw new IllegalStateException("P13 starfield patch marker missing: sky radiance function");
        }

        String helpers = """
                uint p13StarHash(uvec2 cell) {
                    uint value = cell.x * 0x9E3779B1u ^ cell.y * 0x85EBCA77u;
                    value ^= value >> 16u;
                    value *= 0x7FEB352Du;
                    value ^= value >> 15u;
                    value *= 0x846CA68Bu;
                    value ^= value >> 16u;
                    return value;
                }

                float p13StarUnit(uint value) {
                    return float(value & 0xFFFFu) / 65535.0;
                }

                vec2 p13StarSkyUv(vec3 direction) {
                    vec3 dir = normalize(direction);
                    float angle = p13DayPhase() * 6.28318530718;
                    float cosine = cos(angle);
                    float sine = sin(angle);

                    // Moon/Sun travel through the XY celestial plane (around the Z axis).
                    // Sample the procedural dome with the inverse celestial rotation so the
                    // visible star field itself advances in the same direction as the moon.
                    vec3 rotated = vec3(
                        cosine * dir.x + sine * dir.y,
                        -sine * dir.x + cosine * dir.y,
                        dir.z
                    );
                    float denominator = max(
                        abs(rotated.x) + abs(rotated.y) + abs(rotated.z),
                        0.000001
                    );
                    return rotated.xz / denominator * 0.5 + 0.5;
                }

                vec3 p13StarRadiance(vec3 direction, vec3 sunDirection) {
                    vec3 dir = normalize(direction);
                    if (dir.y <= 0.02) return vec3(0.0);

                    float night = 1.0 - smoothstep(-0.16, 0.06, sunDirection.y);
                    float horizonFade = smoothstep(0.04, 0.24, dir.y);
                    // Full-cube sky ambient samples exactly world-up. Keep that single direction
                    // star-free so the visible star field does not turn into a fake point light.
                    float zenithAmbientGuard = 1.0 - smoothstep(0.99995, 1.0, dir.y);
                    float visibility = night * horizonFade * zenithAmbientGuard;
                    if (visibility <= 0.0001) return vec3(0.0);

                    vec2 gridPosition = p13StarSkyUv(dir) * 192.0;
                    uvec2 cell = uvec2(floor(gridPosition));
                    vec2 local = fract(gridPosition) - 0.5;

                    uint primaryHash = p13StarHash(cell);
                    float spawn = p13StarUnit(primaryHash);
                    if (spawn < 0.985) return vec3(0.0);

                    uint secondaryHash = p13StarHash(cell + uvec2(0x68BC21EBu, 0x02E5BE93u));
                    uint tertiaryHash = p13StarHash(cell + uvec2(0x967A889Bu, 0x368CC8B7u));
                    vec2 jitter = vec2(
                        p13StarUnit(secondaryHash),
                        p13StarUnit(tertiaryHash)
                    ) - 0.5;
                    jitter *= 0.58;

                    float radius = mix(0.10, 0.24, p13StarUnit(primaryHash >> 8u));
                    float distanceToStar = length(local - jitter);
                    float point = 1.0 - smoothstep(radius * 0.22, radius, distanceToStar);
                    if (point <= 0.0001) return vec3(0.0);

                    float brightness = mix(0.22, 0.72, p13StarUnit(secondaryHash >> 8u));
                    float temperature = p13StarUnit(tertiaryHash >> 8u);
                    vec3 warm = vec3(1.00, 0.82, 0.66);
                    vec3 cool = vec3(0.66, 0.79, 1.00);
                    vec3 color = mix(warm, cool, temperature);
                    color = mix(color, vec3(1.0), 0.42);

                    return color * (point * brightness * visibility);
                }

                """;
        source = source.replace(skyMarker, helpers + skyMarker);

        String oldSkyReturn = """
                        return sky
                                + p13SunColor(sunDirection) * sunDisk
                                + p13MoonColor() * (moonDisk * 0.72 + moonGlow);
                """;
        String newSkyReturn = """
                        vec3 stars = p13StarRadiance(dir, sunDirection);
                        float nightAmbient = 1.0 - smoothstep(-0.16, 0.06, sunDirection.y);
                        // Stars are not only a visible sky decal: their aggregate contribution
                        // must remain available to hemisphere/environment samples at night.
                        vec3 stellarAmbient = vec3(0.0035, 0.0045, 0.0090) * nightAmbient;
                        return sky
                                + stellarAmbient
                                + stars
                                + p13SunColor(sunDirection) * sunDisk
                                + p13MoonColor() * (moonDisk * 0.72 + moonGlow);
                """;
        source = replaceRequiredOnce(source, oldSkyReturn, newSkyReturn, "Overworld sky star composition");

        TotemLumenClient.LOGGER.info(
                "P13 Overworld starfield active: procedural=true, deterministic=true, celestialAxis=Z, movesWithMoon=true, "
                        + "twinkle=false, horizonFade=true, stellarAmbient=true"
        );
        return source;
    }

    private static String replaceRequiredOnce(String source, String target, String replacement, String label) {
        int first = source.indexOf(target);
        if (first < 0) {
            throw new IllegalStateException("P13 starfield patch marker missing: " + label);
        }
        if (source.indexOf(target, first + target.length()) >= 0) {
            throw new IllegalStateException("P13 starfield patch marker is not unique: " + label);
        }
        return source.substring(0, first) + replacement + source.substring(first + target.length());
    }
}
