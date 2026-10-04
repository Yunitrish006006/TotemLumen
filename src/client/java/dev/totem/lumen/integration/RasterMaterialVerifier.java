package dev.totem.lumen.integration;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.totem.lumen.mixin.RasterMaterialCaptureMixin;
import dev.totem.lumen.mixin.RasterMaterialLayersInvoker;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.lwjgl.util.shaderc.Shaderc;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Compiles both native draw ABIs and checks the private bridge against the configured game jar. */
public final class RasterMaterialVerifier {
    private RasterMaterialVerifier() { }

    public static void verify() throws Exception {
        for (var layer : ChunkSectionLayerGroup.OPAQUE.layers()) {
            for (boolean multi : new boolean[]{false, true}) {
                var nativePipeline = layer.pipeline(multi);
                var capture = RasterMaterialCapture.pipeline(nativePipeline);
                // Builder stores layouts in a hash set; list iteration order is not an ABI.
                if (!java.util.Set.copyOf(capture.getBindGroupLayouts()).equals(java.util.Set.copyOf(nativePipeline.getBindGroupLayouts()))
                        || !capture.getVertexFormatBindings().equals(nativePipeline.getVertexFormatBindings())
                        || !capture.getDepthStencilState().equals(nativePipeline.getDepthStencilState())
                        || !capture.getColorTargetStates().equals(nativePipeline.getColorTargetStates())) {
                    throw new IllegalStateException("Material pipeline ABI mismatch: " + layer + " multi=" + multi
                            + " layouts=" + capture.getBindGroupLayouts().equals(nativePipeline.getBindGroupLayouts())
                            + " vertex=" + capture.getVertexFormatBindings().equals(nativePipeline.getVertexFormatBindings())
                            + " depth=" + capture.getDepthStencilState().equals(nativePipeline.getDepthStencilState())
                            + " target=" + capture.getColorTargetStates().equals(nativePipeline.getColorTargetStates()));
                }
            }
        }
        Class<?>[] parameters = {ChunkSectionLayer[].class, GpuSampler.class, RenderPass.class,
                GpuTextureView.class, GpuTextureView.class, RenderPipeline.class, RenderPipeline.class};
        if (ChunkSectionsToRender.class.getDeclaredMethod("renderLayers", parameters).getReturnType() != void.class
                || RasterMaterialLayersInvoker.class.getDeclaredMethod("totemLumen$renderMaterialLayers", parameters).getReturnType() != void.class) {
            throw new IllegalStateException("Raster material layer invoker ABI mismatch");
        }
        ChunkSectionsToRender.class.getDeclaredMethod("renderGroup", ChunkSectionLayerGroup.class, RenderPass.class,
                GpuSampler.class, GpuTextureView.class, boolean.class);
        RasterMaterialCaptureMixin.class.getDeclaredMethod("totemLumen$rememberOpaque", ChunkSectionLayerGroup.class,
                RenderPass.class, GpuSampler.class, GpuTextureView.class, boolean.class, CallbackInfo.class);
        for (boolean multi : new boolean[]{false, true}) {
            for (boolean cutout : new boolean[]{false, true}) {
                for (boolean vertex : new boolean[]{false, true}) {
                    String path = "assets/totem-lumen/shaders/core/raster_material." + (vertex ? "vsh" : "fsh");
                    String source = expand(path, 0);
                    String defines = (multi ? "#define MULTIDRAW_TERRAIN\n" : "")
                            + (cutout ? "#define ALPHA_CUTOUT 0.1\n" : "");
                    source = source.replace("#version 330", "#version 330\n" + defines);
                    compile(source, path, vertex);
                }
            }
        }
        System.out.println("Raster material verification PASS: 8 shader variants, native opaque bridge ABI; NOT runtime/visual acceptance");
    }

    private static String expand(String path, int depth) throws Exception {
        if (depth > 16) throw new IllegalStateException("Shader include depth exceeded");
        String source;
        try (var input = RasterMaterialVerifier.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing shader " + path);
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var matcher = Pattern.compile("#include <([^:>]+):([^>]+)>").matcher(source);
        StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(expanded, java.util.regex.Matcher.quoteReplacement(
                    expand("assets/" + matcher.group(1) + "/shaders/include/" + matcher.group(2), depth + 1)));
        }
        return matcher.appendTail(expanded).toString();
    }

    private static void compile(String source, String name, boolean vertex) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        if (compiler == 0 || options == 0) {
            if (compiler != 0) Shaderc.shaderc_compiler_release(compiler);
            if (options != 0) Shaderc.shaderc_compile_options_release(options);
            throw new IllegalStateException("Shaderc initialization failed");
        }
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, 0, 4202496);
            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
            long result = Shaderc.shaderc_compile_into_spv(compiler, source,
                    vertex ? Shaderc.shaderc_vertex_shader : Shaderc.shaderc_fragment_shader, name, "main", options);
            if (result == 0) throw new IllegalStateException("Shaderc returned no result");
            try {
                if (Shaderc.shaderc_result_get_compilation_status(result) != 0)
                    throw new IllegalStateException(name + ": " + Shaderc.shaderc_result_get_error_message(result));
                if (Shaderc.shaderc_result_get_length(result) == 0) throw new IllegalStateException("Empty SPIR-V");
            } finally { Shaderc.shaderc_result_release(result); }
        } finally { Shaderc.shaderc_compile_options_release(options); Shaderc.shaderc_compiler_release(compiler); }
    }
}
