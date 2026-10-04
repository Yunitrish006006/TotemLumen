package dev.totem.lumen.integration;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.render.RasterLightingVolume;
import dev.totem.lumen.render.RendererSettings;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/** Owns only the raster-primary INDIRECT_GI pipeline and radiance target. */
final class RasterIndirectGiStage {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("totem-lumen", "pipeline/raster_indirect_gi"))
            .withVertexShader(RenderPipelines.TRACY_BLIT.getShaders().get(ShaderType.VERTEX))
            .withFragmentShader(Identifier.fromNamespaceAndPath("totem-lumen", "core/raster_indirect_gi"))
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("NormalSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("VoxelSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(
                    Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .build();

    private static GpuDevice device;
    private static GpuTexture radiance;
    private static GpuTextureView radianceView;
    private static long frames;
    private static boolean logged;

    private RasterIndirectGiStage() { }

    static RasterLightingFrame record(
            CommandEncoder encoder,
            GpuDevice gpu,
            RasterSurfaceFrame surface,
            RasterVoxelSceneFrame voxelScene,
            GpuBufferSlice uniforms,
            GpuSampler nearest
    ) {
        if (voxelScene == null || !voxelScene.coherent()) {
            throw new IllegalArgumentException("INDIRECT_GI requires a coherent shared VOXEL_SCENE");
        }

        int width = surface.width(), height = surface.height();
        int lightingWidth = RendererSettings.internalResolution().targetWidth(width, height);
        int lightingHeight = Math.max(1, Math.round(lightingWidth * height / (float) width));
        ensureTarget(gpu, lightingWidth, lightingHeight);

        var compiled = RenderSystem.getCompiledPipeline(PIPELINE);
        try (var pass = encoder.createRenderPass(
                () -> "Raster INDIRECT_GI secondary rays", radianceView, Optional.empty())) {
            pass.setPipeline(compiled);
            pass.setUniform("DynamicTransforms", uniforms);
            pass.setUniform("DepthSampler", surface.depth(), nearest);
            pass.setUniform("NormalSampler", surface.normal(), nearest);
            pass.setUniform("VoxelSampler", voxelScene.voxelAtlas(), nearest);
            pass.draw(3, 1, 0, 0);
        }

        frames++;
        if (!logged) {
            logged = true;
            TotemLumenClient.LOGGER.info(
                    "RASTER INDIRECT_GI ACTIVE: {}x{}, rayDistance={}, samples={}, sharedVoxelScene=true, primaryRays=0",
                    lightingWidth,
                    lightingHeight,
                    RasterLightingVolume.RAY_DISTANCE,
                    RendererSettings.giQuality().samples()
            );
        }
        return new RasterLightingFrame(
                gpu,
                lightingWidth,
                lightingHeight,
                surface.frameSerial(),
                voxelScene.completeScene(),
                radianceView
        );
    }

    private static void ensureTarget(GpuDevice gpu, int width, int height) {
        if (device == gpu
                && radiance != null
                && radiance.getWidth(0) == width
                && radiance.getHeight(0) == height) {
            return;
        }

        close();
        device = gpu;
        radiance = gpu.createTexture(
                "Raster INDIRECT_GI radiance and distance",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA16_FLOAT,
                width, height, 1, 1);
        radianceView = gpu.createTextureView(radiance);
    }

    static void close() {
        if (radianceView != null) radianceView.close();
        if (radiance != null) radiance.close();
        radianceView = null;
        radiance = null;
        device = null;
        if (logged) {
            TotemLumenClient.LOGGER.info(
                    "Raster INDIRECT_GI resources retired: frames={}", frames);
        }
        frames = 0;
        logged = false;
    }
}
