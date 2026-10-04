package dev.totem.lumen.vulkan;

/** Deterministic tests against the complete production shader, including P17 and fluid optics. */
public final class PrimarySurfaceReuseVerifier {
    public static void main(String[] args) {
        String original = P12FullBasePipeline.buildSourceForVerification();
        String optimized = PrimarySurfaceReuseShaderPatch.apply(original);
        for (String marker : new String[]{"vec3 p13OneBounceIndirectRgb(", "vec3 giIndirectCurrentRgb(",
                "uint giTemporalIndirectColor(", "uint giCompositeFromIndirect(",
                "vec3 p13EnvironmentWithSurface(", "uint localLightWithSurface("}) {
            String function = PrimarySurfaceReuseShaderPatch.function(optimized, marker);
            require(!function.contains("p18ResolveSurface("), "Repeated decode: " + marker);
            require(function.substring(0, function.indexOf('{')).contains("P18SurfaceSample"),
                    "Missing explicit value parameter: " + marker);
        }
        // Shading arithmetic is unchanged; only the source of p18Surface is different.
        sameBody(original, optimized, "vec3 p13EnvironmentSurfaceRadiance(", "vec3 p13EnvironmentWithSurface(");
        sameBody(original, optimized, "uint localLightColor(", "uint localLightWithSurface(");
        for (String marker : new String[]{"HitResult traceRayLimited(", "P15TraceResult p15TraceFiltered(",
                "vec3 p13SkyVisibility(", "vec3 tlRuntimeDirectionalTransmission("}) {
            require(PrimarySurfaceReuseShaderPatch.function(original, marker).equals(
                    PrimarySurfaceReuseShaderPatch.function(optimized, marker)), "Traversal changed: " + marker);
        }
        String main = PrimarySurfaceReuseShaderPatch.function(optimized, "void main()");
        require(count(main, "p18ResolveSurface(") == 1, "Primary decode must appear once in GI hit branch");
        require(main.contains("} else {\n            P18SurfaceSample primarySurface"), "Decode moved out of GI hit branch");
        require(main.contains("giHistorySamples, primarySurface"), "History path did not forward surface");
        require(main.contains("indirectColor, primarySurface"), "Composite did not receive same surface");
        require(optimized.contains("sampleIndex * giSamples + sampleOffset, p18PrimarySurface"), "GI loop did not forward surface");
        require(optimized.contains("p13FrameSeed(), p18PrimarySurface"), "Temporal path did not forward surface");
        require(optimized.contains("localLightWithSurface(hit, primaryOrigin, primaryDirection, true, p18Surface)"),
                "Composite local light did not reuse surface");
        require(optimized.contains("p13EnvironmentWithSurface(hit, primaryOrigin, primaryDirection, p18Surface)"),
                "Composite environment did not reuse surface");
        require(optimized.contains("return p13EnvironmentWithSurface(hit, rayOrigin, rayDirection, p18ResolveSurface(hit, rayOrigin, rayDirection))"),
                "Bounce/environment wrapper lost its own material evaluation");
        rejects(() -> PrimarySurfaceReuseShaderPatch.apply(optimized));
        rejects(() -> PrimarySurfaceReuseShaderPatch.apply(original + original));
        rejects(() -> PrimarySurfaceReuseShaderPatch.apply(original.replace("uint indirectColor = giTemporalIndirectColor(", "drift(")));
        rejects(() -> PrimarySurfaceReuseShaderPatch.apply(P16MultipassReflection.buildSourceForVerification()));
        require(LumenShaderVariant.select("baseline", optimized).equals(optimized), "Baseline composition changed");
        require(PrimarySurfaceReuseShaderPatch.apply(FilteredTraceShaderPatch.apply(original)).equals(
                FilteredTraceShaderPatch.apply(optimized)), "Trace variant transforms interfere");
        require(PrimarySurfaceReuseShaderPatch.apply(HybridTerrainShaderPatch.apply(original)).equals(
                HybridTerrainShaderPatch.apply(optimized)), "Hybrid transforms interfere");
        System.out.println("Primary surface reuse PASS: invocation-local explicit parameters, single primary decode, "
                + "unchanged lighting bodies/traversal, own bounce materials, candidate/hybrid composition, fail-closed drift");
    }

    private static void sameBody(String before, String after, String oldMarker, String newMarker) {
        String oldFunction = PrimarySurfaceReuseShaderPatch.function(before, oldMarker);
        String newFunction = PrimarySurfaceReuseShaderPatch.function(after, newMarker);
        String oldBody = oldFunction.substring(oldFunction.indexOf('{')).replaceAll(
                "P18SurfaceSample p18Surface = p18ResolveSurface\\([^;]+;", "");
        String newBody = newFunction.substring(newFunction.indexOf('{'));
        require(oldBody.replaceAll("\\s+", "").equals(newBody.replaceAll("\\s+", "")),
                "Lighting arithmetic changed: " + oldMarker);
    }

    private static int count(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }

    private static void rejects(Runnable operation) {
        try { operation.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejection of drift/duplicate/wrong pass");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
