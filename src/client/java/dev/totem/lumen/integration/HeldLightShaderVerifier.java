package dev.totem.lumen.integration;

import org.lwjgl.util.shaderc.Shaderc;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Build-time compilation gate for the held-light fragment shader's Minecraft shader ABI. */
public final class HeldLightShaderVerifier {
    private static final String DYNAMIC_TRANSFORMS = "assets/minecraft/shaders/include/dynamictransforms.glsl";

    private HeldLightShaderVerifier() {
    }

    public static void main(String[] args) throws Exception {
        RasterMaterialVerifier.verify();
        ClientHeldLightFieldVerifier.verify();
        String include = readResource(DYNAMIC_TRANSFORMS);
        String directive = "#include <minecraft:dynamictransforms.glsl>";
        verify(readResource("assets/totem-lumen/shaders/core/hybrid_terrain.fsh").replace(directive, include),
                "totem-lumen:core/hybrid_terrain");
        verify(readResource("assets/totem-lumen/shaders/core/raster_indirect_gi.fsh").replace(directive, include), "totem-lumen:core/raster_indirect_gi");
        verify(readResource("assets/totem-lumen/shaders/core/raster_ray_composite.fsh").replace(directive, include), "totem-lumen:core/raster_ray_composite");
    }

    private static void verify(String source, String name) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        if (compiler == 0L || options == 0L) {
            throw new IllegalStateException("Failed to initialize shaderc for held-light verification");
        }
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, 0, 4202496);
            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
            long result = Shaderc.shaderc_compile_into_spv(
                    compiler, source, Shaderc.shaderc_fragment_shader,
                    name, "main", options);
            if (result == 0L) {
                throw new IllegalStateException("Held-light fragment returned no shaderc result");
            }
            try {
                if (Shaderc.shaderc_result_get_compilation_status(result) != 0) {
                    throw new IllegalStateException("Held-light fragment compilation failed: "
                            + Shaderc.shaderc_result_get_error_message(result));
                }
                ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
                if (spirv == null || !spirv.hasRemaining()) {
                    throw new IllegalStateException("Held-light fragment compiled to empty SPIR-V");
                }
                System.out.println("Held-light fragment shader verification PASS: " + name
                        + " bytes=" + spirv.remaining());
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static String readResource(String path) throws Exception {
        try (InputStream stream = HeldLightShaderVerifier.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Required shader resource is missing: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

}
