package dev.totem.lumen.integration;

import org.lwjgl.util.shaderc.Shaderc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Build-time verifier retained under the historical class name so existing developer tasks do not
 * drift while the raster material replay is replaced by the owned SURFACE_CAPTURE stage.
 */
public final class RasterMaterialVerifier {
    private RasterMaterialVerifier() { }

    public static void main(String[] args) throws Exception {
        verify();
    }

    public static void verify() throws Exception {
        var capture = RasterSurfaceCapture.class.getDeclaredMethod("capture");
        if (capture.getReturnType() != RasterSurfaceFrame.class) {
            throw new IllegalStateException("Raster surface capture must publish RasterSurfaceFrame");
        }

        String path = "assets/totem-lumen/shaders/core/raster_surface_depth.fsh";
        String source = resource(path);
        if (!source.contains("uniform sampler2D DepthSampler")
                || !source.contains("float depth = texture(DepthSampler, texCoord).r")) {
            throw new IllegalStateException("Owned depth capture shader contract drift");
        }
        compile(path, source);

        if (!RasterSurfaceFrame.ColorSemantic.NATIVE_LIT_COLOR.name().equals("NATIVE_LIT_COLOR")) {
            throw new IllegalStateException("Raster surface color semantic drift");
        }

        System.out.println("Raster surface verification PASS: owned color/depth handoff and depth shader compile; NOT runtime/visual acceptance");
    }

    private static String resource(String path) throws IOException {
        try (var input = RasterMaterialVerifier.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing shader " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void compile(String name, String source) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        if (compiler == 0 || options == 0) {
            if (compiler != 0) Shaderc.shaderc_compiler_release(compiler);
            if (options != 0) Shaderc.shaderc_compile_options_release(options);
            throw new IllegalStateException("shaderc initialization failed");
        }
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, 0, 4202496);
            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
            long result = Shaderc.shaderc_compile_into_spv(
                    compiler, source, Shaderc.shaderc_fragment_shader, name, "main", options);
            if (result == 0) throw new IllegalStateException("shaderc returned null result");
            try {
                if (Shaderc.shaderc_result_get_compilation_status(result) != 0) {
                    throw new IllegalStateException(name + ": " + Shaderc.shaderc_result_get_error_message(result));
                }
                if (Shaderc.shaderc_result_get_length(result) == 0) {
                    throw new IllegalStateException("Empty SPIR-V");
                }
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }
}
