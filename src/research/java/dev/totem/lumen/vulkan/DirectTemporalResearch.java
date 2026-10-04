package dev.totem.lumen.vulkan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Research-only specialization for the audited full shader; never used by player rendering. */
final class DirectTemporalResearch {
    static final String VARIANT = "direct-temporal";
    // Pin the whole audited source, including all callers and mode dispatch. Source drift
    // must trigger a new audit rather than silently extending this specialization's scope.
    private static final String AUDITED_SOURCE_SHA256 =
            "4371aba28c49e28304a2d84f3d0b51e52ed94cfebc9ab1c24cf8935313c5438e";
    private static final String OLD = """
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
            """;
    private static final String NEW = """
            uint currentTemporalSampleColor(
                    HitResult hit,
                    vec3 primaryOrigin,
                    vec3 primaryDirection,
                    uvec2 pixel
            ) {
                // Research: this full shader calls temporalHistoryColor only in modes 8/9.
                uint sampleIndex = scene.data[41];
                return temporalCurrentColor(hit, primaryOrigin, primaryDirection, sampleIndex);
            }
            """;

    static String apply(String source) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
            if (!hash.equals(AUDITED_SOURCE_SHA256))
                throw new IllegalArgumentException("Direct-temporal source drift: re-audit callers and scene ABI");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        int at = source.indexOf(OLD);
        if (at < 0 || source.indexOf(OLD, at + OLD.length()) >= 0)
            throw new IllegalArgumentException("Missing or ambiguous direct-temporal helper");
        // Preserve the entire history body, GI paths, main, transmission and output verbatim.
        return source.substring(0, at) + NEW + source.substring(at + OLD.length());
    }
}
