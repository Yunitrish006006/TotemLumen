package dev.totem.lumen.vulkan;

import org.lwjgl.util.shaderc.Shaderc;

import java.lang.reflect.Field;

/** Build-time verifier for the production P12-P15 base pass and split P16 reflection pass. */
public final class P14ShaderCompileVerifier {
    private static final String BASE_SHADER_NAME = "totem_lumen_p12_one_bounce_gi.comp";

    private P14ShaderCompileVerifier() {
    }

    public static void main(String[] args) throws Exception {
        Field shaderField = P5StableLookupRenderer.class.getDeclaredField("SHADER");
        shaderField.setAccessible(true);
        String baseSource = (String) shaderField.get(null);

        baseSource = P12GiShaderPatch.apply(baseSource);
        baseSource = P13EndBrightnessPatch.apply(baseSource);
        baseSource = P14GeometryShaderPatch.apply(baseSource);
        baseSource = P14CommonGeometryPatch.apply(baseSource);
        baseSource = P14GeometryCorrectionPatch.apply(baseSource);
        baseSource = P13SkyOcclusionPatch.apply(baseSource);
        baseSource = P16ReflectionRoughnessPatch.apply(baseSource);

        verifyP13MoonSource(baseSource);
        String reflectionSource = P16ReflectionPassShader.build();

        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            throw new IllegalStateException("Failed to initialize shaderc for runtime shader verification");
        }

        try {
            compileAndVerify(compiler, BASE_SHADER_NAME, baseSource, "P12-P15 base pass");
            compileAndVerify(
                    compiler,
                    P16ReflectionPassShader.SHADER_NAME,
                    reflectionSource,
                    "P16 split reflection pass"
            );
        } finally {
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static void verifyP13MoonSource(String source) {
        requireSourceMarker(source, "vec3 p13MoonDirection()", "moon direction");
        requireSourceMarker(source, "float p13MoonStrength(vec3 moonDirection)", "moon horizon gating");
        requireSourceMarker(source, "float p13MoonDisk(vec3 direction, vec3 moonDirection)", "moon disk");
        requireSourceMarker(source, "vec3 moon = p13MoonColor()", "moon surface lighting");
        requireSourceMarker(source, "vec3 moonTransmission = vec3(0.0);", "P15 moon transmission");
        System.out.println(
                "P13 moon shader verification PASS: disk=true, coldDirectionalLight=true, "
                        + "oppositeSun=true, p15Transmission=true"
        );
    }

    private static void requireSourceMarker(String source, String marker, String label) {
        if (!source.contains(marker)) {
            throw new IllegalStateException("P13 moon shader verification missing " + label + ": " + marker);
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

            long result = Shaderc.shaderc_compile_into_spv(
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
                    printLineRange(source, 1, Math.min(120, source.split("\\R", -1).length));
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
