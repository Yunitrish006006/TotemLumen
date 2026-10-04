package dev.totem.lumen.integration;

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
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/** Stateless COMPOSITE stage. It is the only staged raster pass that writes the presentation target. */
final class RasterCompositeStage {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("totem-lumen", "pipeline/raster_composite"))
            .withVertexShader(RenderPipelines.TRACY_BLIT.getShaders().get(ShaderType.VERTEX))
            .withFragmentShader(Identifier.fromNamespaceAndPath("totem-lumen", "core/raster_ray_composite"))
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("LightingSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("SceneSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(
                    Optional.empty(),
                    RenderPipelines.DEBUG_FILLED_BOX.getColorTargetStates().getFirst().format(),
                    ColorTargetState.WRITE_ALL))
            .build();

    private RasterCompositeStage() { }

    static void record(
            CommandEncoder encoder,
            GpuTextureView presentationTarget,
            GpuBufferSlice uniforms,
            GpuSampler nearest,
            RasterSurfaceFrame surface,
            RasterLightingFrame lighting
    ) {
        if (!lighting.matches(surface)) {
            throw new IllegalArgumentException("COMPOSITE received lighting from a different surface frame");
        }
        var compiled = com.mojang.blaze3d.systems.RenderSystem.getCompiledPipeline(PIPELINE);
        try (var pass = encoder.createRenderPass(
                () -> "Raster COMPOSITE", presentationTarget, Optional.empty())) {
            pass.setPipeline(compiled);
            pass.setUniform("DynamicTransforms", uniforms);
            pass.setUniform("DepthSampler", surface.depth(), nearest);
            pass.setUniform("LightingSampler", lighting.radiance(), nearest);
            pass.setUniform("SceneSampler", surface.baseColor(), nearest);
            pass.draw(3, 1, 0, 0);
        }
    }
}
