package dev.totem.lumen.vulkan;

import org.lwjgl.util.shaderc.Shaderc;

/** Build-time verifier for bootstrap readiness and all staged production compute passes. */
public final class P14ShaderCompileVerifier {
    private static final String BOOTSTRAP_SHADER_NAME = "totem_lumen_p12_one_bounce_gi.comp";

    private P14ShaderCompileVerifier() {
    }

    public static void main(String[] args) {
        String bootstrapSource = P5BootstrapShader.source();
        verifyBootstrapSource(bootstrapSource);

        String baseSource = P12FullBasePipeline.buildSourceForVerification();
        verifyP13NightSkySource(baseSource);
        verifyFullBaseIsolation(baseSource);
        verifyP14EFluidSource(baseSource, "full base pass");
        verifyP14EFluidOptics(baseSource, "full base pass", false);

        String p17Source = P17ShaderIntegration.apply(baseSource);
        verifyP14EFluidSource(p17Source, "P17 enhanced base pass");
        verifyP14EFluidOptics(p17Source, "P17 enhanced base pass", false);
        verifyP17DynamicEntitySource(p17Source, "enhanced base pass");

        String reflectionSource = P17ShaderIntegration.apply(
                P14EFluidOpticsPatch.apply(
                        P14EFluidShaderPatch.apply(P16ReflectionPassShader.build())
                )
        );
        verifyP13NightSkySource(reflectionSource);
        verifyP14EFluidSource(reflectionSource, "P16 reflection pass");
        verifyP14EFluidOptics(reflectionSource, "P16 reflection pass", true);
        verifyP17DynamicEntitySource(reflectionSource, "P16 reflection pass");

        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            throw new IllegalStateException("Failed to initialize shaderc for runtime shader verification");
        }

        try {
            compileAndVerify(
                    compiler,
                    BOOTSTRAP_SHADER_NAME,
                    bootstrapSource,
                    "Vulkan bootstrap readiness pass"
            );
            compileAndVerify(
                    compiler,
                    P12FullBasePipeline.SHADER_NAME,
                    baseSource,
                    "P12-P15+P14E full base pass"
            );
            compileAndVerify(
                    compiler,
                    P17EnhancedBasePipeline.SHADER_NAME,
                    p17Source,
                    "P14E+P17 enhanced base pass"
            );
            compileAndVerify(
                    compiler,
                    P16ReflectionPassShader.SHADER_NAME,
                    reflectionSource,
                    "P14E+P16+P17 split reflection pass"
            );
        } finally {
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static void verifyBootstrapSource(String source) {
        requireSourceMarker(source, "uint materialAt(ivec3 voxel)", "bootstrap material lookup");
        requireSourceMarker(source, "HitResult traceRayLimited(", "bootstrap voxel DDA");
        requireSourceMarker(source, "vec3 bootstrapColor(", "bootstrap output");
        if (source.contains("p13Moon")
                || source.contains("P14E_FLUID_ABI_VERSION")
                || source.contains("P17_ENTITY_MATERIAL_ID")
                || source.contains("p16ReflectionRgb")
                || source.contains("temporalHistoryColor")) {
            throw new IllegalStateException("Bootstrap shader accidentally contains staged renderer features");
        }
        if (source.length() > 16_000) {
            throw new IllegalStateException(
                    "Bootstrap shader exceeded cold-start size budget: " + source.length() + " chars"
            );
        }
        System.out.println(
                "Vulkan bootstrap readiness verification PASS: chars=" + source.length()
                        + ", voxelDda=true, stagedGi=false, p14e=false, p17=false, reflection=false"
        );
    }

    private static void verifyP13NightSkySource(String source) {
        requireSourceMarker(source, "uint p13MoonPhase()", "packed moon phase");
        requireSourceMarker(source, "float p13MoonPhaseBrightness()", "phase-weighted moonlight");
        requireSourceMarker(source, "float p13MoonPhaseMask(vec3 direction, vec3 moonDirection)", "phase silhouette");
        requireSourceMarker(source, "vec3 p13MoonDirection()", "moon direction");
        requireSourceMarker(source, "float p13MoonStrength(vec3 moonDirection)", "moon horizon/phase gating");
        requireSourceMarker(source, "float p13MoonDisk(vec3 direction, vec3 moonDirection)", "moon disk");
        requireSourceMarker(source, "vec3 moon = p13MoonColor()", "moon surface lighting");
        requireSourceMarker(source, "vec3 moonTransmission = vec3(0.0);", "P15 moon transmission");
        requireSourceMarker(source, "uint p13StarHash(uvec2 cell)", "procedural star hash");
        requireSourceMarker(source, "vec2 p13StarSkyUv(vec3 direction)", "rotating star dome");
        requireSourceMarker(source, "vec3 p13StarRadiance(vec3 direction, vec3 sunDirection)", "star radiance");
        requireSourceMarker(source, "vec3 stars = p13StarRadiance(dir, sunDirection);", "star sky composition");
        requireSourceMarker(source, "return scene.data[41] & 0xFFFFu;", "full stochastic frame seed");
        System.out.println(
                "P13 night-sky shader verification PASS: moon=true, phaseSteps=8, stars=true, "
                        + "deterministicStars=true, rotatingStarDome=true, fullFrameSeed=true, p15Transmission=true"
        );
    }

    private static void verifyFullBaseIsolation(String source) {
        if (source.contains("P17_ENTITY_MATERIAL_ID")) {
            throw new IllegalStateException("P17 must remain outside the full P12-P15 base pipeline");
        }
        System.out.println(
                "Staged readiness verification PASS: bootstrap->P12-P15+P14E->P17/P16, "
                        + "fullBaseContainsP17=false"
        );
    }

    private static void verifyP14EFluidSource(String source, String label) {
        requireSourceMarker(source, "const uint P14E_FLUID_ABI_VERSION = 1u;", label + " fluid ABI");
        requireSourceMarker(source, "uint p14eFindFluidCell(", label + " block-coordinate fluid lookup");
        requireSourceMarker(source, "bool p14eIntersectFluidCell(", label + " exact fluid quad trace");
        requireSourceMarker(source, "bool p14eFluidOnly = false;", label + " pure-fluid suppression state");
        requireSourceMarker(source, "bool p14eStaticHit = false;", label + " waterlogged static coexistence");
        requireSourceMarker(
                source,
                "p14eFluidDistance < p14eStaticDistance - 0.00001",
                label + " in-cell nearest-hit competition"
        );
        requireSourceMarker(source, "P14E_WATER_MATERIAL_ID", label + " water surface identity");
        requireSourceMarker(source, "P14E_LAVA_MATERIAL_ID", label + " lava surface identity");
        System.out.println(
                "P14E exact-fluid shader verification PASS (" + label + "): "
                        + "blockLookup=true, resolvedQuads=true, waterloggedCoexistence=true, nearestHit=true"
        );
    }

    private static void verifyP14EFluidOptics(String source, String label, boolean reflectionPass) {
        requireSourceMarker(source, "vec3 p14eResolvedFluidTint(HitResult hit)", label + " resolved water tint");
        requireSourceMarker(source, "vec4 p14eWaterTransmission(HitResult hit)", label + " water transmission");
        requireSourceMarker(
                source,
                "bool p14eWaterSurface = candidate.materialId == P14E_WATER_MATERIAL_ID;",
                label + " P15 water interface"
        );
        requireSourceMarker(
                source,
                "advance = candidate.distance + 0.002;",
                label + " exact-interface advance"
        );
        requireSourceMarker(
                source,
                "if (materialId == P14E_LAVA_MATERIAL_ID) return vec4(1.00, 0.12, 0.015, 1.0);",
                label + " lava emission"
        );
        if (reflectionPass) {
            requireSourceMarker(
                    source,
                    "if (hit.materialId == P14E_WATER_MATERIAL_ID) return vec2(0.025, 0.0);",
                    label + " low-roughness water reflection"
            );
            requireSourceMarker(
                    source,
                    "bool p14eReflectWater = p14eRawPrimary.hit != 0u",
                    label + " raw exact-water reflection surface"
            );
        }
        System.out.println(
                "P14E fluid-optics verification PASS (" + label + "): "
                        + "waterTransmission=true, resolvedTint=true, lavaEmission=true, exactWaterReflection="
                        + reflectionPass
        );
    }

    private static void verifyP17DynamicEntitySource(String source, String label) {
        requireSourceMarker(source, "const uint P17_ENTITY_MATERIAL_ID = 0xFFFEu;", label + " entity material id");
        requireSourceMarker(source, "HitResult p17TraceStaticRayLimited(", label + " static trace preservation");
        requireSourceMarker(source, "bool p17TrySectionEntities(", label + " section broad phase");
        requireSourceMarker(source, "HitResult p17TraceEntityRayLimited(", label + " entity trace");
        requireSourceMarker(
                source,
                "HitResult traceRayLimited(vec3 origin, vec3 direction, float maxDistance) {",
                label + " shared nearest-hit entry"
        );
        requireSourceMarker(
                source,
                "if (candidate.materialId == P17_ENTITY_MATERIAL_ID)",
                label + " P15 opaque entity baseline"
        );
        System.out.println(
                "P17 dynamic-entity shader verification PASS (" + label + "): "
                        + "sectionBroadPhase=true, triangles=true, nearestHit=true, p15OpaqueBaseline=true"
        );
    }

    private static void requireSourceMarker(String source, String marker, String label) {
        if (!source.contains(marker)) {
            throw new IllegalStateException("Runtime shader verification missing " + label + ": " + marker);
        }
    }

    private static void compileAndVerify(long compiler, String shaderName, String source, String label) {
        long options = Shaderc.shaderc_compile_options_initialize();
        if (options == 0L) {
            throw new IllegalStateException("Failed to initialize shaderc options for " + label);
        }

        try {
            Shaderc.shaderc_compile_options_set_target_env(options, 0, 4202496);
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_zero);
            System.out.println(label + " verification START: chars=" + source.length() + ", optimization=O0");

            long result = ShadercNativeHeap.compileIntoSpv(
                    compiler,
                    source,
                    Shaderc.shaderc_compute_shader,
                    shaderName,
                    "main",
                    options
            );
            if (result == 0L) {
                throw new IllegalStateException("shaderc returned a null result for " + label);
            }
            try {
                int status = Shaderc.shaderc_result_get_compilation_status(result);
                if (status != 0) {
                    printLineRange(source, 1, Math.min(160, source.split("\\R", -1).length));
                    throw new IllegalStateException(
                            label + " verification failed: " + Shaderc.shaderc_result_get_error_message(result)
                    );
                }
                System.out.println(
                        label + " verification PASS: warnings="
                                + Shaderc.shaderc_result_get_num_warnings(result)
                                + ", errors="
                                + Shaderc.shaderc_result_get_num_errors(result)
                                + ", bytes="
                                + Shaderc.shaderc_result_get_length(result)
                );
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
        }
    }

    private static void printLineRange(String source, int firstLine, int lastLine) {
        String[] lines = source.split("\\R", -1);
        int first = Math.max(1, firstLine);
        int last = Math.min(lines.length, lastLine);
        System.out.println("--- transformed shader lines " + first + ".." + last + " ---");
        for (int line = first; line <= last; line++) {
            System.out.printf("%04d | %s%n", line, lines[line - 1]);
        }
        System.out.println("--- end transformed shader context ---");
    }
}
