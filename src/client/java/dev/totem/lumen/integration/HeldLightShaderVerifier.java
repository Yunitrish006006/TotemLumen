package dev.totem.lumen.integration;

import org.lwjgl.util.shaderc.Shaderc;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Build-time compilation gate for the held-light fragment shader's Minecraft shader ABI. */
public final class HeldLightShaderVerifier {
    private static final String FRAGMENT = "assets/totem-lumen/shaders/core/held_light.fsh";
    private static final String RGB_FRAGMENT = "assets/totem-lumen/shaders/core/held_light_rgb.fsh";
    private static final String DYNAMIC_TRANSFORMS = "assets/minecraft/shaders/include/dynamictransforms.glsl";

    private HeldLightShaderVerifier() {
    }

    public static void main(String[] args) throws Exception {
        String fragment = readResource(FRAGMENT);
        String rgbFragment = readResource(RGB_FRAGMENT);
        String include = readResource(DYNAMIC_TRANSFORMS);
        String directive = "#include <minecraft:dynamictransforms.glsl>";
        if (!fragment.contains(directive) || !rgbFragment.contains(directive)) {
            throw new IllegalStateException("Held-light fragment does not include Minecraft dynamic transforms");
        }
        if (!fragment.contains("float radialDistance = distanceToLight * max(TextureMat[0].w, 1.0)")
                || !rgbFragment.contains("float radialDistance = length(ModelOffset - worldPosition) * max(TextureMat[0].w, 1.0)")
                || !rgbFragment.contains("texture(SceneColorSampler, texCoord)")
                || !rgbFragment.contains("texelFetch(PlacedRgbSampler, texel, 0)")
                || !rgbFragment.contains("vec3 fraction = fract(voxelPosition)")
                || !rgbFragment.contains("float shapedFalloff = 1.2 * falloff * falloff * (1.5 - 0.5 * falloff)")
                || !rgbFragment.contains("vec3 missing = max(held - placed, vec3(0.0))")
                || !rgbFragment.contains("reflectance * missing * (vec3(1.0) - scene)")) {
            throw new IllegalStateException("Held light must subtract placed RGB illumination before compositing");
        }
        verifyAtlasLayout();
        verify(fragment.replace(directive, include), "totem-lumen:core/held_light");
        verify(rgbFragment.replace(directive, include), "totem-lumen:core/held_light_rgb");
    }

    private static void verifyAtlasLayout() {
        boolean[] occupied = new boolean[HeldLightFieldAtlas.WIDTH * HeldLightFieldAtlas.HEIGHT];
        for (int slot = 0; slot < 4; slot++) {
            for (int z = 0; z < HeldLightFieldAtlas.SIDE; z++) {
                for (int y = 0; y < HeldLightFieldAtlas.SIDE; y++) {
                    for (int x = 0; x < HeldLightFieldAtlas.SIDE; x++) {
                        int atlasX = HeldLightFieldAtlas.atlasX(slot, x, z);
                        int atlasY = HeldLightFieldAtlas.atlasY(slot, y, z);
                        int index = atlasY * HeldLightFieldAtlas.WIDTH + atlasX;
                        if (atlasX < 0 || atlasX >= HeldLightFieldAtlas.WIDTH || atlasY < 0
                                || atlasY >= HeldLightFieldAtlas.HEIGHT || occupied[index]) {
                            throw new IllegalStateException("Held-light RGB atlas has an overlapping or invalid cell");
                        }
                        occupied[index] = true;
                    }
                }
            }
        }
        for (boolean cell : occupied) {
            if (!cell) throw new IllegalStateException("Held-light RGB atlas has a missing cell");
        }
        if (HeldLightFieldAtlas.origin(-0.2f) != -17) {
            throw new IllegalStateException("Held-light RGB atlas must floor negative world coordinates");
        }
        char[][] first = new char[27][];
        char[][] unchanged = first.clone();
        if (!HeldLightFieldAtlas.sameReferences(first, unchanged)) {
            throw new IllegalStateException("Unchanged RGB sections should not cause an atlas upload");
        }
        unchanged[3] = new char[4096];
        if (HeldLightFieldAtlas.sameReferences(first, unchanged)) {
            throw new IllegalStateException("Changed RGB sections must refresh the atlas");
        }
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
