package dev.totem.lumen.integration;

import com.mojang.blaze3d.platform.NativeImage;
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
import dev.totem.lumen.render.RasterLightingWindow;
import dev.totem.lumen.render.RendererSettings;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

import java.util.Optional;

/** Owns the raster-primary INDIRECT_GI pipeline, voxel atlas and radiance target. */
final class RasterIndirectGiStage {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("totem-lumen", "pipeline/raster_indirect_gi"))
            .withVertexShader(RenderPipelines.TRACY_BLIT.getShaders().get(ShaderType.VERTEX))
            .withFragmentShader(Identifier.fromNamespaceAndPath("totem-lumen", "core/raster_indirect_gi"))
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("VoxelSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(
                    Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .build();

    private static GpuDevice device;
    private static GpuTexture atlas, radiance;
    private static GpuTextureView atlasView, radianceView;
    private static NativeImage tile;
    private static final RasterLightingVolume.Section[] uploaded =
            new RasterLightingVolume.Section[RasterLightingVolume.SLOTS];
    private static long epoch = -1, frames, uploadBytes;
    private static boolean logged;

    private RasterIndirectGiStage() { }

    /**
     * Records atlas uploads and, when the upload window is coherent, one INDIRECT_GI draw.
     *
     * @return frame output, or null when uploads were recorded but lighting must wait for a later frame.
     */
    static RasterLightingFrame record(
            CommandEncoder encoder,
            GpuDevice gpu,
            RasterSurfaceFrame surface,
            RasterLightingVolume volume,
            GpuBufferSlice uniforms,
            GpuSampler nearest
    ) {
        int width = surface.width(), height = surface.height();
        int lightingWidth = RendererSettings.internalResolution().targetWidth(width, height);
        int lightingHeight = Math.max(1, Math.round(lightingWidth * height / (float) width));
        ensureTargets(gpu, lightingWidth, lightingHeight);

        if (epoch != volume.epoch) {
            java.util.Arrays.fill(uploaded, null);
            epoch = volume.epoch;
            encoder.clearColorTexture(atlas, new Vector4f());
        }

        boolean complete = true;
        boolean pendingUploads = false;
        int uploadsThisFrame = 0;
        for (int slot = 0; slot < uploaded.length; slot++) {
            var section = volume.section(slot);
            if (section == null) complete = false;
            if (section == uploaded[slot]) continue;
            if (uploadsThisFrame >= RasterLightingWindow.UPLOADS_PER_FRAME) {
                pendingUploads = true;
                continue;
            }
            for (int i = 0; i < 4096; i++) {
                tile.setPixelABGR(i & 15, i >>> 4, section == null ? 0 : section.voxel(i));
            }
            encoder.writeToTexture(
                    atlas, tile, 0, 0,
                    RasterLightingVolume.tileX(slot), RasterLightingVolume.tileY(slot));
            uploaded[slot] = section;
            uploadBytes += 16384;
            uploadsThisFrame++;
        }

        // Never shade against stale atlas slots after a rebase/invalidation. The coordinator still
        // submits the uploads recorded above so a later frame can progress.
        if (pendingUploads) return null;

        var compiled = RenderSystem.getCompiledPipeline(PIPELINE);
        try (var pass = encoder.createRenderPass(
                () -> "Raster INDIRECT_GI secondary rays", radianceView, Optional.empty())) {
            pass.setPipeline(compiled);
            pass.setUniform("DynamicTransforms", uniforms);
            pass.setUniform("DepthSampler", surface.depth(), nearest);
            pass.setUniform("VoxelSampler", atlasView, nearest);
            pass.draw(3, 1, 0, 0);
        }

        frames++;
        if (!logged) {
            logged = true;
            TotemLumenClient.LOGGER.info(
                    "RASTER INDIRECT_GI ACTIVE: {}x{}, rayDistance={}, samples={}, atlasBytes={}, primaryRays=0",
                    lightingWidth,
                    lightingHeight,
                    RasterLightingVolume.RAY_DISTANCE,
                    RendererSettings.giQuality().samples(),
                    RasterLightingVolume.ATLAS_WIDTH * RasterLightingVolume.ATLAS_HEIGHT * 4
            );
        }
        return new RasterLightingFrame(
                gpu, lightingWidth, lightingHeight, surface.frameSerial(), complete, radianceView);
    }

    private static void ensureTargets(GpuDevice gpu, int width, int height) {
        if (device == gpu
                && radiance != null
                && radiance.getWidth(0) == width
                && radiance.getHeight(0) == height) {
            return;
        }

        close();
        device = gpu;
        atlas = gpu.createTexture(
                "Raster INDIRECT_GI voxel atlas",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING
                        | GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.RGBA8_UNORM,
                RasterLightingVolume.ATLAS_WIDTH,
                RasterLightingVolume.ATLAS_HEIGHT,
                1, 1);
        atlasView = gpu.createTextureView(atlas);
        radiance = gpu.createTexture(
                "Raster INDIRECT_GI radiance and distance",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA16_FLOAT,
                width, height, 1, 1);
        radianceView = gpu.createTextureView(radiance);
        tile = new NativeImage(16, 256, false);
    }

    static void close() {
        if (atlasView != null) atlasView.close();
        if (atlas != null) atlas.close();
        if (radianceView != null) radianceView.close();
        if (radiance != null) radiance.close();
        if (tile != null) tile.close();
        atlasView = radianceView = null;
        atlas = radiance = null;
        tile = null;
        device = null;
        java.util.Arrays.fill(uploaded, null);
        epoch = -1;
        if (logged) {
            TotemLumenClient.LOGGER.info(
                    "Raster INDIRECT_GI resources retired: frames={}, uploadBytes={}", frames, uploadBytes);
        }
        frames = uploadBytes = 0;
        logged = false;
    }
}
