package dev.totem.lumen.integration;

import net.minecraft.client.renderer.state.level.CameraRenderState;
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
        var capture = RasterSurfaceCapture.class.getDeclaredMethod("capture", CameraRenderState.class);
        if (capture.getReturnType() != RasterSurfaceFrame.class) {
            throw new IllegalStateException("Raster surface capture must publish RasterSurfaceFrame");
        }

        String depthPath = "assets/totem-lumen/shaders/core/raster_surface_depth.fsh";
        String depthSource = resource(depthPath);
        if (!depthSource.contains("uniform sampler2D DepthSampler")
                || !depthSource.contains("float depth = texture(DepthSampler, texCoord).r")) {
            throw new IllegalStateException("Owned depth capture shader contract drift");
        }
        compile(depthPath, depthSource);

        String normalPath = "assets/totem-lumen/shaders/core/raster_surface_normal.fsh";
        String normalSource = resource(normalPath);
        String dynamicTransforms = resource("assets/minecraft/shaders/include/dynamictransforms.glsl");
        String directive = "#include <minecraft:dynamictransforms.glsl>";
        if (!normalSource.contains(directive)
                || !normalSource.contains("vec3 n = cross(dx, dy)")
                || !normalSource.contains("fragColor = vec4(n * 0.5 + 0.5, 1.0)")) {
            throw new IllegalStateException("Owned normal capture shader contract drift");
        }
        compile(normalPath, normalSource.replace(directive, dynamicTransforms));

        String materialPath = "assets/totem-lumen/shaders/core/raster_material_resolve.fsh";
        String materialSource = resource(materialPath);
        if (!materialSource.contains(directive)
                || !materialSource.contains("uniform sampler2D MaterialIdAtlas")
                || !materialSource.contains("p + ModelOffset - normal * 0.08")
                || !materialSource.contains("fragColor = surfaceIdentity(cell)")) {
            throw new IllegalStateException("Material resolve shader contract drift");
        }
        compile(materialPath, materialSource.replace(directive, dynamicTransforms));

        String decodeDirective = "#include <totem-lumen:raster_material_decode.glsl>";
        String decodeSource = resource("assets/totem-lumen/shaders/include/raster_material_decode.glsl");
        String propertiesPath = "assets/totem-lumen/shaders/core/raster_material_properties.fsh";
        String propertiesSource = resource(propertiesPath);
        if (!propertiesSource.contains(decodeDirective)
                || !propertiesSource.contains("uniform sampler2D VisibleSurfaceIdentity")
                || !propertiesSource.contains("uniform sampler2D MaterialLut")
                || !propertiesSource.contains("rasterMaterialFloat(MaterialLut, materialId, 2u)")
                || !propertiesSource.contains("fragColor = vec4(roughness, metallic, opacity, emission)")) {
            throw new IllegalStateException("Resolved material property shader contract drift");
        }
        compile(propertiesPath, propertiesSource.replace(decodeDirective, decodeSource));

        if (!RasterSurfaceFrame.ColorSemantic.NATIVE_LIT_COLOR.name().equals("NATIVE_LIT_COLOR")) {
            throw new IllegalStateException("Raster surface color semantic drift");
        }

        System.out.println("Raster staged verification PASS: owned surface + material identity/base-property resolve shaders compile; NOT runtime/visual acceptance");
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
