package dev.totem.lumen.vulkan;

import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/** Invocation-local value reuse, applied after material/entity/fluid transforms. No scene ABI change. */
final class PrimarySurfaceReuseShaderPatch {
    static final String PROPERTY = "totem.lumen.reusePrimarySurface";
    static final boolean ENABLED = Boolean.getBoolean(PROPERTY);

    private PrimarySurfaceReuseShaderPatch() { }

    static String apply(String source) {
        if (source.contains("p13EnvironmentWithSurface")) {
            throw new IllegalArgumentException("Primary surface reuse already applied");
        }
        source = withSurfaceHelper(source, "vec3", "p13EnvironmentSurfaceRadiance",
                "p13EnvironmentWithSurface", "hit, rayOrigin, rayDirection",
                "hit, rayOrigin, rayDirection", "p18Surface");
        source = withSurfaceHelper(source, "uint", "localLightColor",
                "localLightWithSurface", "hit, primaryOrigin, primaryDirection, emissiveMode",
                "hit, primaryOrigin, primaryDirection", "p18Surface");

        source = edit(source, "vec3 p13OneBounceIndirectRgb(", f -> {
            f = parameter(f, "P18SurfaceSample p18PrimarySurface");
            return removeResolve(f, "p18PrimarySurface", "primaryHit, primaryOrigin, primaryDirection");
        });
        source = edit(source, "vec3 giIndirectCurrentRgb(", f -> {
            f = parameter(f, "P18SurfaceSample p18PrimarySurface");
            return once(f, "sampleIndex * giSamples + sampleOffset",
                    "sampleIndex * giSamples + sampleOffset, p18PrimarySurface");
        });
        source = edit(source, "uint giTemporalIndirectColor(", f -> {
            f = parameter(f, "P18SurfaceSample p18PrimarySurface");
            return once(f, "giIndirectCurrentRgb(hit, primaryOrigin, primaryDirection, pixel, p13FrameSeed())",
                    "giIndirectCurrentRgb(hit, primaryOrigin, primaryDirection, pixel, p13FrameSeed(), p18PrimarySurface)");
        });
        source = edit(source, "uint giCompositeFromIndirect(", f -> {
            f = parameter(f, "P18SurfaceSample p18Surface");
            f = removeResolve(f, "p18Surface", "hit, primaryOrigin, primaryDirection");
            f = regexOnce(f, "p13EnvironmentSurfaceRadiance\\(\\s*hit,\\s*primaryOrigin,\\s*primaryDirection\\s*\\)",
                    "p13EnvironmentWithSurface(hit, primaryOrigin, primaryDirection, p18Surface)");
            return once(f, "localLightColor(hit, primaryOrigin, primaryDirection, true)",
                    "localLightWithSurface(hit, primaryOrigin, primaryDirection, true, p18Surface)");
        });
        return edit(source, "void main()", f -> {
            // Only the GI hit branch resolves this sample: sky/debug modes retain their old path.
            f = once(f, "uint indirectColor = giTemporalIndirectColor(",
                    "P18SurfaceSample primarySurface = p18ResolveSurface(primaryHit, origin, direction);\n"
                            + "            uint indirectColor = giTemporalIndirectColor(");
            f = once(f, "primaryHit, origin, direction, pixel, width, height, giHistorySamples",
                    "primaryHit, origin, direction, pixel, width, height, giHistorySamples, primarySurface");
            return once(f, "primaryHit, origin, direction, indirectColor",
                    "primaryHit, origin, direction, indirectColor, primarySurface");
        });
    }

    private static String withSurfaceHelper(String source, String type, String name, String helper,
                                           String args, String resolveArgs, String value) {
        return edit(source, type + " " + name + "(", original -> {
            int brace = original.indexOf('{');
            String header = original.substring(0, brace);
            String body = removeResolve(original, value, resolveArgs);
            body = parameter(body, "P18SurfaceSample " + value);
            body = once(body, type + " " + name + "(", type + " " + helper + "(");
            // Keep non-GI callers (including bounce hits and debug lighting) semantically identical.
            return body + "\n\n" + header + "{\n    return " + helper + "(" + args
                    + ", p18ResolveSurface(" + resolveArgs + "));\n}";
        });
    }

    private static String removeResolve(String source, String value, String args) {
        return regexOnce(source, "P18SurfaceSample " + value + " = p18ResolveSurface\\(\\s*"
                + Pattern.quote(args) + "\\s*\\);", "");
    }

    private static String parameter(String function, String parameter) {
        int close = function.lastIndexOf(')', function.indexOf('{'));
        if (close < 0) throw new IllegalArgumentException("Missing function signature");
        return function.substring(0, close).stripTrailing() + ",\n        " + parameter
                + "\n" + function.substring(close);
    }

    static String function(String source, String marker) {
        int start = unique(source, marker);
        int open = source.indexOf('{', start);
        if (open < 0) throw new IllegalArgumentException("Missing body: " + marker);
        int depth = 1;
        for (int end = open + 1; end < source.length(); end++) {
            char c = source.charAt(end);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return source.substring(start, end + 1);
        }
        throw new IllegalArgumentException("Unclosed body: " + marker);
    }

    private static String edit(String source, String marker, UnaryOperator<String> edit) {
        String old = function(source, marker);
        return once(source, old, edit.apply(old));
    }

    private static int unique(String source, String text) {
        int at = source.indexOf(text);
        if (at < 0 || source.indexOf(text, at + text.length()) >= 0) {
            throw new IllegalArgumentException("Missing/ambiguous primary reuse anchor: " + text);
        }
        return at;
    }

    private static String once(String source, String old, String replacement) {
        int at = unique(source, old);
        return source.substring(0, at) + replacement + source.substring(at + old.length());
    }

    private static String regexOnce(String source, String regex, String replacement) {
        var matcher = Pattern.compile(regex).matcher(source);
        if (!matcher.find()) throw new IllegalArgumentException("Missing primary reuse pattern: " + regex);
        int start = matcher.start(), end = matcher.end();
        if (matcher.find()) throw new IllegalArgumentException("Ambiguous primary reuse pattern: " + regex);
        return source.substring(0, start) + replacement + source.substring(end);
    }
}
