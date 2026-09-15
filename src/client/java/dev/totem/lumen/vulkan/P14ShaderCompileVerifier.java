package dev.totem.lumen.vulkan;

import org.lwjgl.util.shaderc.Shaderc;

import java.lang.reflect.Field;

/**
 * Build-time verifier for the exact monolithic shader source used by the live P5-P16 renderer.
 *
 * <p>The production renderer compiles this shader during background prewarm because Minecraft owns
 * the Vulkan device. CI verifies the exact transform + production O0 shaderc path without creating
 * a Vulkan device, preventing malformed runtime shader transforms from reaching players.</p>
 */
public final class P14ShaderCompileVerifier {
    private static final String SHADER_NAME = "totem_lumen_p12_one_bounce_gi.comp";

    private P14ShaderCompileVerifier() {
    }

    public static void main(String[] args) throws Exception {
        Field shaderField = P5StableLookupRenderer.class.getDeclaredField("SHADER");
        shaderField.setAccessible(true);
        String source = (String) shaderField.get(null);

        source = P12GiShaderPatch.apply(source);
        source = P13EndBrightnessPatch.apply(source);
        source = P14GeometryShaderPatch.apply(source);
        source = P14CommonGeometryPatch.apply(source);
        source = P14GeometryCorrectionPatch.apply(source);
        source = P13SkyOcclusionPatch.apply(source);
        source = P16ReflectionRoughnessPatch.apply(source);

        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        if (compiler == 0L || options == 0L) {
            throw new IllegalStateException("Failed to initialize shaderc for runtime shader verification");
        }

        try {
            Shaderc.shaderc_compile_options_set_target_env(options, 0, 4202496);
            Shaderc.shaderc_compile_options_set_optimization_level(options, 0);
            System.out.println("P16 runtime shader verification START: chars=" + source.length() + ", optimization=O0");

            long result = Shaderc.shaderc_compile_into_spv(
                    compiler,
                    source,
                    Shaderc.shaderc_compute_shader,
                    SHADER_NAME,
                    "main",
                    options
            );
            if (result == 0L) {
                throw new IllegalStateException("shaderc returned a null result");
            }
            try {
                int status = Shaderc.shaderc_result_get_compilation_status(result);
                if (status != 0) {
                    printLineRange(source, 900, 1010);
                    throw new IllegalStateException(
                            "Runtime shader verification failed: " + Shaderc.shaderc_result_get_error_message(result)
                    );
                }
                System.out.println(
                        "P16 runtime shader verification PASS: warnings="
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
            Shaderc.shaderc_compiler_release(compiler);
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
