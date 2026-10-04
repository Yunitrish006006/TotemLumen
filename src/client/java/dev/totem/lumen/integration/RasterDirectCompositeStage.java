package dev.totem.lumen.integration;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.totem.lumen.TotemLumenClient;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Opt-in COMPOSITE implementation that consumes DIRECT_LIGHT only where MATERIAL_RESOLVE coverage
 * is explicit. Uncovered and far surfaces retain the accepted native-lit fallback.
 */
final class RasterDirectCompositeStage {
    static final String PROPERTY = "totem.lumen.rasterDirectLightComposite";
    private static final boolean REQUESTED = Boolean.getBoolean(PROPERTY);

    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("totem-lumen", "pipeline/raster_direct_composite"))
            .withVertexShader(RenderPipelines.TRACY_BLIT.getShaders().get(ShaderType.VERTEX))
            .withFragmentShader(Identifier.fromNamespaceAndPath("totem-lumen", "core/raster_direct_composite"))
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("IndirectSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("NativeSceneSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("UnlitAlbedoSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("DirectSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(
                    Optional.empty(),
                    RenderPipelines.DEBUG_FILLED_BOX.getColorTargetStates().getFirst().format(),
                    ColorTargetState.WRITE_ALL))
            .build();

    private static boolean logged;

    private RasterDirectCompositeStage() { }

    static boolean requested() {
        return REQUESTED;
    }

    static boolean enabled() {
        return REQUESTED && RasterDirectLightStage.enabled();
    }

    static void record(
            CommandEncoder encoder,
            GpuTextureView presentationTarget,
            GpuBufferSlice uniforms,
            GpuSampler nearest,
            RasterSurfaceFrame surface,
            RasterMaterialFrame material,
            RasterLightingFrame indirect,
            RasterDirectLightFrame direct
    ) {
        if (!enabled()) {
            throw new IllegalStateException("DIRECT_LIGHT composite was recorded while disabled");
        }
        if (!indirect.matches(surface)
                || !material.matches(surface)
                || !direct.matches(surface, material)) {
            throw new IllegalArgumentException("DIRECT_LIGHT COMPOSITE received mixed frame generations");
        }

        var compiled = RenderSystem.getCompiledPipeline(PIPELINE);
        try (var pass = encoder.createRenderPass(
                () -> "Raster DIRECT_LIGHT coverage composite", presentationTarget, Optional.empty())) {
            pass.setPipeline(compiled);
            pass.setUniform("DynamicTransforms", uniforms);
            pass.setUniform("DepthSampler", surface.depth(), nearest);
            pass.setUniform("IndirectSampler", indirect.radiance(), nearest);
            pass.setUniform("NativeSceneSampler", surface.baseColor(), nearest);
            pass.setUniform("UnlitAlbedoSampler", material.unlitAlbedo(), nearest);
            pass.setUniform("DirectSampler", direct.radiance(), nearest);
            pass.draw(3, 1, 0, 0);
        }

        if (!logged) {
            logged = true;
            TotemLumenClient.LOGGER.info(
                    "RASTER DIRECT_LIGHT composite diagnostic ACTIVE: coverageOnly=true, nearFieldOnly=true, nativeFallback=true, sunSky=false");
        }
    }

    static void close() {
        logged = false;
    }
}
