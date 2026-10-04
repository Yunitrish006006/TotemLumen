package dev.totem.lumen.vulkan;

/** Opt-in candidate transformation; baseline remains the default runtime shader. */
final class FilteredTraceShaderPatch {
    static final String VARIANT = "shared-filtered-trace";
    private static final String START =
            "P15TraceResult p15TraceFiltered(vec3 origin, vec3 direction, float maxDistance) {";
    private static final String END = "\nvec3 p15RayTransmission(";
    private static final String OLD_LOOP = """
                for (uint layer = 0u; layer < 8u; layer++) {
                    if (layer >= maxLayers) break;
                    HitResult candidate = traceRayLimited(cursorOrigin, dir, remaining);
            """;
    private static final String NEW_LOOP = """
                // Research: one static trace call, including the terminal layer.
                for (uint layer = 0u; layer <= 8u; layer++) {
                    HitResult candidate = traceRayLimited(cursorOrigin, dir, remaining);
                    if (layer >= maxLayers) {
                        // A terminal miss deliberately does not add traveled.
                        if (candidate.hit != 0u) candidate.distance += traveled;
                        result.hit = candidate;
                        return result;
                    }
            """;
    private static final String OLD_TERMINAL = """
                // More than eight transparent voxels is treated conservatively as a blocker.
                HitResult terminal = traceRayLimited(cursorOrigin, dir, remaining);
                if (terminal.hit != 0u) terminal.distance += traveled;
                result.hit = terminal;
                return result;
            """;
    private static final String NEW_TERMINAL = """
                // maxLayers is clamped to 1..8, so the terminal iteration returns above.
                return result;
            """;

    static String apply(String source) {
        int start = uniqueIndex(source, START);
        int end = source.indexOf(END, start);
        if (end < 0) throw new IllegalArgumentException("Missing filtered trace end");
        String before = source.substring(start, end);
        uniqueIndex(before, "uint maxLayers = clamp(scene.data[61], 1u, 8u);");
        if (count(before, "traceRayLimited(") != 2)
            throw new IllegalArgumentException("Expected exactly two filtered trace call sites");
        String after = replaceOnce(replaceOnce(before, OLD_LOOP, NEW_LOOP), OLD_TERMINAL, NEW_TERMINAL);
        if (count(after, "traceRayLimited(") != 1)
            throw new IllegalStateException("Candidate must have exactly one filtered trace call site");
        // Guards make drift fail closed. All ordinary-layer statements remain byte-identical.
        return source.substring(0, start) + after + source.substring(end);
    }

    private static String replaceOnce(String source, String oldText, String newText) {
        int at = uniqueIndex(source, oldText);
        return source.substring(0, at) + newText + source.substring(at + oldText.length());
    }

    private static int uniqueIndex(String source, String text) {
        int at = source.indexOf(text);
        if (at < 0 || source.indexOf(text, at + text.length()) >= 0)
            throw new IllegalArgumentException("Missing or ambiguous research source anchor: " + text);
        return at;
    }

    private static int count(String source, String text) {
        int count = 0;
        for (int at = 0; (at = source.indexOf(text, at)) >= 0; at += text.length()) count++;
        return count;
    }
}
