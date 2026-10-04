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
import dev.totem.lumen.render.RasterDirectLights;
import dev.totem.lumen.render.RasterLightingVolume;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * Optional DIRECT_LIGHT producer for the staged raster renderer.
 *
 * <p>This stage is intentionally diagnostic until COMPOSITE consumes its output. It owns only
 * the bounded direct-emitter payload and direct-radiance target; the voxel atlas is shared through
 * {@link RasterVoxelSceneFrame}.</p>
 */
final class RasterDirectLightStage {
    static final String PROPERTY = "totem.lumen.rasterDirectLightStage";
    private static final boolean REQUESTED = Boolean.getBoolean(PROPERTY);

    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("totem-lumen", "pipeline/raster_direct_light"))
            .withVertexShader(RenderPipelines.TRACY_BLIT.getShaders().get(ShaderType.VERTEX))
            .withFragmentShader(Identifier.fromNamespaceAndPath("totem-lumen", "core/raster_direct_light"))
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("NormalSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("UnlitAlbedoSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("VoxelSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("LightSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(
                    Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .build();

    private static GpuDevice device;
    private static GpuTexture radiance, lightPayload;
    private static GpuTextureView radianceView, lightPayloadView;
    private static NativeImage lightPixels;

    private static RasterLightingVolume selectedVolume;
    private static List<RasterDirectLights.Light> selectedLights = List.of();
    private static int selectedX, selectedY, selectedZ;
    private static long frames, selections, selectionNanos, uploadBytes;
    private static boolean failed, logged;

    private RasterDirectLightStage() { }

    static boolean requested() {
        return REQUESTED;
    }

    static boolean enabled() {
        return REQUESTED && RasterMaterialResolveStage.enabled() && !failed;
    }

    static void fail(Throwable failure) {
        if (failed) return;
        failed = true;
        TotemLumenClient.LOGGER.error(
                "Raster DIRECT_LIGHT disabled for this session; INDIRECT_GI/COMPOSITE remain available",
                failure);
        close();
    }

    static RasterDirectLightFrame record(
            CommandEncoder encoder,
            GpuDevice gpu,
            RasterSurfaceFrame surface,
            RasterMaterialFrame material,
            RasterVoxelSceneFrame voxelScene,
            RasterLightingVolume volume,
            GpuBufferSlice uniforms,
            GpuSampler nearest,
            double cameraLocalX,
            double cameraLocalY,
            double cameraLocalZ
    ) {
        if (!enabled()) return null;
        if (material == null || !material.matches(surface)) {
            throw new IllegalArgumentException("DIRECT_LIGHT requires MATERIAL_RESOLVE from the same surface frame");
        }
        if (voxelScene == null || !voxelScene.coherent() || !voxelScene.matches(gpu, volume)) {
            throw new IllegalArgumentException("DIRECT_LIGHT requires the coherent shared VOXEL_SCENE");
        }

        ensureTargets(gpu, surface.width(), surface.height());
        updateLightPayload(encoder, volume, cameraLocalX, cameraLocalY, cameraLocalZ);

        var compiled = RenderSystem.getCompiledPipeline(PIPELINE);
        try (var pass = encoder.createRenderPass(
                () -> "Raster DIRECT_LIGHT bounded RGB emitters", radianceView, Optional.empty())) {
            pass.setPipeline(compiled);
            pass.setUniform("DynamicTransforms", uniforms);
            pass.setUniform("DepthSampler", surface.depth(), nearest);
            pass.setUniform("NormalSampler", surface.normal(), nearest);
            pass.setUniform("UnlitAlbedoSampler", material.unlitAlbedo(), nearest);
            pass.setUniform("VoxelSampler", voxelScene.voxelAtlas(), nearest);
            pass.setUniform("LightSampler", lightPayloadView, nearest);
            pass.draw(3, 1, 0, 0);
        }

        frames++;
        if (!logged) {
            logged = true;
            TotemLumenClient.LOGGER.info(
                    "RASTER DIRECT_LIGHT diagnostic ACTIVE: coverage=material-unlit-alpha, selectedLights={}/{}, shadowRays<=4, sharedVoxelScene=true, compositeConsumer=false",
                    selectedLights.size(),
                    RasterDirectLights.MAX_LIGHTS
            );
        }
        return new RasterDirectLightFrame(
                gpu,
                surface.width(),
                surface.height(),
                surface.frameSerial(),
                selectedLights.size(),
                radianceView
        );
    }

    private static void ensureTargets(GpuDevice gpu, int width, int height) {
        if (device == gpu
                && radiance != null
                && radiance.getWidth(0) == width
                && radiance.getHeight(0) == height
                && lightPayload != null) {
            return;
        }

        close();
        device = gpu;
        radiance = gpu.createTexture(
                "Raster DIRECT_LIGHT radiance and coverage",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA16_FLOAT,
                width, height, 1, 1);
        radianceView = gpu.createTextureView(radiance);

        lightPayload = gpu.createTexture(
                "Raster DIRECT_LIGHT bounded RGB emitters",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM,
                RasterDirectLights.TEXELS,
                1, 1, 1);
        lightPayloadView = gpu.createTextureView(lightPayload);
        lightPixels = new NativeImage(RasterDirectLights.TEXELS, 1, false);
    }

    private static void updateLightPayload(
            CommandEncoder encoder,
            RasterLightingVolume volume,
            double cameraLocalX,
            double cameraLocalY,
            double cameraLocalZ
    ) {
        int cx = (int) Math.floor(cameraLocalX);
        int cy = (int) Math.floor(cameraLocalY);
        int cz = (int) Math.floor(cameraLocalZ);
        boolean firstPayload = selectedVolume == null;
        if (!firstPayload
                && selectedVolume == volume
                && selectedX == cx
                && selectedY == cy
                && selectedZ == cz) {
            return;
        }

        long started = System.nanoTime();
        List<RasterDirectLights.Light> lights =
                RasterDirectLights.select(volume, cx + 0.5, cy + 0.5, cz + 0.5);
        selections++;
        selectionNanos += System.nanoTime() - started;

        if (firstPayload || !lights.equals(selectedLights)) {
            for (int i = 0; i < RasterDirectLights.MAX_LIGHTS; i++) {
                lightPixels.setPixelABGR(
                        i * 2,
                        0,
                        i < lights.size() ? lights.get(i).positionTexel() : 0);
                lightPixels.setPixelABGR(
                        i * 2 + 1,
                        0,
                        i < lights.size() ? lights.get(i).abgr() : 0);
            }
            encoder.writeToTexture(lightPayload, lightPixels, 0, 0, 0, 0);
            uploadBytes += (long) RasterDirectLights.TEXELS * 4;
        }

        selectedVolume = volume;
        selectedLights = lights;
        selectedX = cx;
        selectedY = cy;
        selectedZ = cz;
    }

    static void close() {
        if (radianceView != null) radianceView.close();
        if (lightPayloadView != null) lightPayloadView.close();
        if (radiance != null) radiance.close();
        if (lightPayload != null) lightPayload.close();
        if (lightPixels != null) lightPixels.close();

        radianceView = lightPayloadView = null;
        radiance = lightPayload = null;
        lightPixels = null;
        device = null;
        selectedVolume = null;
        selectedLights = List.of();

        if (logged) {
            TotemLumenClient.LOGGER.info(
                    "Raster DIRECT_LIGHT resources retired: frames={}, selections={}, selectionMs={}, uploadBytes={}",
                    frames, selections, selectionNanos / 1_000_000.0, uploadBytes);
        }
        frames = selections = selectionNanos = uploadBytes = 0;
        logged = false;
    }
}
