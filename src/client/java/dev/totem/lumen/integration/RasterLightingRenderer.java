package dev.totem.lumen.integration;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.render.BackendProbe;
import dev.totem.lumen.render.RasterDirectLights;
import dev.totem.lumen.render.RasterLightingVolume;
import dev.totem.lumen.render.RasterLightingWindow;
import dev.totem.lumen.render.RendererSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import java.util.Optional;

/** Independent Vulkan raster-primary experiment. Does not initialize the full Totem pipeline. */
public final class RasterLightingRenderer {
    private static final RenderPipeline LIGHTING = pipeline("raster_ray", GpuFormat.RGBA16_FLOAT,
            "DepthSampler", "VoxelSampler", "LightSampler");
    private static final RenderPipeline COMPOSITE = pipeline("raster_ray_composite",
            RenderPipelines.DEBUG_FILLED_BOX.getColorTargetStates().getFirst().format(), "DepthSampler", "LightingSampler", "SceneSampler");
    private static GpuDevice device;
    private static GpuTexture atlas, sceneCopy, lighting;
    private static GpuTextureView atlasView, sceneView, lightingView;
    private static NativeImage tile;
    private static GpuTexture directLights;
    private static GpuTextureView directView;
    private static NativeImage directPixels;
    private static RasterLightingVolume selectedVolume;
    private static java.util.List<RasterDirectLights.Light> selectedLights = java.util.List.of();
    private static int selectedX, selectedY, selectedZ;
    private static long lightSelections, lightSelectionNanos, lightUploadBytes;
    private static final RasterLightingVolume.Section[] uploaded = new RasterLightingVolume.Section[RasterLightingVolume.SLOTS];
    private static long epoch = -1, frames, uploadBytes;
    private static boolean failed, ready, logged;

    private RasterLightingRenderer() { }

    private static RenderPipeline pipeline(String name, GpuFormat format, String... names) {
        var samplers = BindGroupLayout.builder();
        for (String sampler : names) samplers.withUniform(sampler, UniformType.COMBINED_IMAGE_SAMPLER);
        return RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("totem-lumen", "pipeline/" + name))
                .withVertexShader(RenderPipelines.TRACY_BLIT.getShaders().get(ShaderType.VERTEX))
                .withFragmentShader(Identifier.fromNamespaceAndPath("totem-lumen", "core/" + name))
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withBindGroupLayout(samplers.build())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL)).build();
    }

    public static boolean ready() { return ready && !failed && RendererSettings.rasterLightingEnabled(); }
    public static boolean unavailable() { return failed; }

    public static void tickLifecycle(Minecraft client) {
        if (!RendererSettings.rasterLightingEnabled() || client.level == null) close();
    }

    public static void render(CameraRenderState camera) {
        if (!RendererSettings.rasterLightingEnabled() || failed) return;
        ready = false;
        // Raw capture is already presented; do not shade it as if it were native lit colour.
        if (RasterMaterialCapture.rawPreviewRequested()) return;
        var volume = RasterLightingScene.snapshot();
        if (camera == null || !camera.initialized || volume == null) { ready = false; return; }
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (target == null || target.getColorTexture() == null || target.getDepthTextureView() == null) { ready = false; return; }
        if (!BackendProbe.detect().vulkan()) { ready = false; return; }
        try {
            var gpu = RenderSystem.getDevice();
            var color = target.getColorTexture();
            int w = color.getWidth(0), h = color.getHeight(0);
            boolean materialPreview = RasterMaterialCapture.lightingPreviewRequested();
            var material = RasterMaterialCapture.lightingFrame(gpu, w, h);
            // Explicit preview fails closed to the untouched native frame, never stale albedo.
            if (materialPreview && material == null) return;
            int lw = RendererSettings.internalResolution().targetWidth(w, h);
            int lh = Math.max(1, Math.round(lw * h / (float) w));
            if (device != gpu || sceneCopy == null || sceneCopy.getWidth(0) != w || sceneCopy.getHeight(0) != h
                    || sceneCopy.getFormat() != color.getFormat() || lighting.getWidth(0) != lw || lighting.getHeight(0) != lh) {
                close(); device = gpu;
                atlas = gpu.createTexture("Raster ray voxel atlas", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                        GpuFormat.RGBA8_UNORM, RasterLightingVolume.ATLAS_WIDTH, RasterLightingVolume.ATLAS_HEIGHT, 1, 1);
                atlasView = gpu.createTextureView(atlas);
                sceneCopy = gpu.createTexture("Raster ray color input", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                        color.getFormat(), w, h, 1, 1);
                sceneView = gpu.createTextureView(sceneCopy);
                lighting = gpu.createTexture("Raster ray radiance and distance", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                        GpuFormat.RGBA16_FLOAT, lw, lh, 1, 1);
                lightingView = gpu.createTextureView(lighting);
                tile = new NativeImage(16, 256, false);
            }
            var lightPipeline = RenderSystem.getCompiledPipeline(LIGHTING);
            var compositePipeline = RenderSystem.getCompiledPipeline(COMPOSITE);
            var encoder = gpu.createCommandEncoder();
            if (epoch != volume.epoch) {
                java.util.Arrays.fill(uploaded, null); epoch = volume.epoch;
                // Zero means unknown, never an occluder. Do not sample uninitialized GPU memory.
                encoder.clearColorTexture(atlas, new Vector4f());
            }
            boolean complete = true;
            boolean pendingUploads = false;
            int uploadsThisFrame = 0;
            for (int slot = 0; slot < uploaded.length; slot++) {
                var section = volume.section(slot);
                if (section == null) complete = false;
                if (section == uploaded[slot]) continue;
                if (uploadsThisFrame >= RasterLightingWindow.UPLOADS_PER_FRAME) { pendingUploads = true; continue; }
                for (int i = 0; i < 4096; i++) tile.setPixelABGR(i & 15, i >>> 4, section == null ? 0 : section.voxel(i));
                encoder.writeToTexture(atlas, tile, 0, 0, RasterLightingVolume.tileX(slot), RasterLightingVolume.tileY(slot));
                uploaded[slot] = section; uploadBytes += 16384; uploadsThisFrame++;
            }
            // A rebased/invalidated atlas must not shade with old slot contents while budgeted
            // uploads catch up. Submit copies only; native world rendering remains the fallback.
            if (pendingUploads) { encoder.submit(); ready = false; return; }
            if (materialPreview) {
                boolean created = directLights == null;
                if (created) {
                    directLights = gpu.createTexture("Raster bounded direct RGB emitters", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                            GpuFormat.RGBA8_UNORM, RasterDirectLights.TEXELS, 1, 1, 1);
                    directView = gpu.createTextureView(directLights);
                    directPixels = new NativeImage(RasterDirectLights.TEXELS, 1, false);
                }
                int cx = (int) Math.floor(camera.pos.x - volume.x), cy = (int) Math.floor(camera.pos.y - volume.y), cz = (int) Math.floor(camera.pos.z - volume.z);
                if (created || selectedVolume != volume || cx != selectedX || cy != selectedY || cz != selectedZ) {
                    long started = System.nanoTime();
                    var lights = RasterDirectLights.select(volume, cx + .5, cy + .5, cz + .5);
                    lightSelections++; lightSelectionNanos += System.nanoTime() - started;
                    selectedVolume = volume; selectedX = cx; selectedY = cy; selectedZ = cz;
                    if (created || !lights.equals(selectedLights)) {
                        for (int i = 0; i < RasterDirectLights.MAX_LIGHTS; i++) {
                            directPixels.setPixelABGR(i * 2, 0, i < lights.size() ? lights.get(i).positionTexel() : 0);
                            directPixels.setPixelABGR(i * 2 + 1, 0, i < lights.size() ? lights.get(i).abgr() : 0);
                        }
                        encoder.writeToTexture(directLights, directPixels, 0, 0, 0, 0);
                        lightUploadBytes += RasterDirectLights.TEXELS * 4;
                    }
                    selectedLights = lights;
                }
            }
            if (!materialPreview) encoder.copyTextureToTexture(color, sceneCopy, 0, 0, 0, 0, 0, w, h);
            Matrix4f inverse = new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix).invert();
            Matrix4f metadata = new Matrix4f().zero().setColumn(0, new Vector4f(RasterLightingVolume.RAY_DISTANCE,
                    RendererSettings.giQuality().samples(), gpu.getDeviceInfo().isZZeroToOne() ? 1 : 0,
                    ClientLightingWorldRules.tuning().brightnessMultiplier()))
                    .setColumn(1, new Vector4f(materialPreview ? 1 : 0, 0, 0, 0));
            Vector3f offset = new Vector3f((float) (camera.pos.x - volume.x),
                    (float) (camera.pos.y - volume.y), (float) (camera.pos.z - volume.z));
            var uniforms = RenderSystem.getDynamicUniforms().writeTransform(inverse, new Vector4f(1), offset, metadata);
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            try (var pass = encoder.createRenderPass(() -> "Raster-primary secondary rays", lightingView, Optional.empty())) {
                pass.setPipeline(lightPipeline);
                pass.setUniform("DynamicTransforms", uniforms);
                pass.setUniform("DepthSampler", materialPreview ? material.depth() : target.getDepthTextureView(), nearest);
                pass.setUniform("VoxelSampler", atlasView, nearest);
                pass.setUniform("LightSampler", materialPreview ? directView : atlasView, nearest);
                pass.draw(3, 1, 0, 0);
            }
            try (var pass = encoder.createRenderPass(() -> "Raster ray depth-aware composite", target.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(compositePipeline);
                pass.setUniform("DynamicTransforms", uniforms);
                pass.setUniform("DepthSampler", materialPreview ? material.depth() : target.getDepthTextureView(), nearest);
                pass.setUniform("LightingSampler", lightingView, nearest);
                pass.setUniform("SceneSampler", materialPreview ? material.color() : sceneView, nearest);
                pass.draw(3, 1, 0, 0);
            }
            encoder.submit(); frames++; ready = complete;
            if (complete && !logged) {
                logged = true;
                if (materialPreview) TotemLumenClient.LOGGER.info("RASTER_MATERIAL RGB lighting preview ACTIVE: directLights={}/32, shadowRays<=4, same-frame material/depth; opaque/cutout only, NOT production lighting", selectedLights.size());
                TotemLumenClient.LOGGER.info("RASTER_RAY ACTIVE: {}x{}, rayDistance={}, giSamples={}, atlasBytes={}, primaryRays=0; "
                        + "approximate normals, cube occlusion, emissive one-bounce only; no transmission/refraction/PBR", lw, lh,
                        RasterLightingVolume.RAY_DISTANCE, RendererSettings.giQuality().samples(), RasterLightingVolume.ATLAS_WIDTH * RasterLightingVolume.ATLAS_HEIGHT * 4);
            }
        } catch (RuntimeException failure) {
            failed = true; ready = false;
            TotemLumenClient.LOGGER.error("Raster ray experiment disabled; retaining Minecraft rendering", failure);
            close();
        }
    }

    public static void close() {
        ready = false;
        if (atlas == null && sceneCopy == null && lighting == null) return;
        // RenderPearl owns deferred GPU retirement; never destroy raw Vulkan handles here.
        if (atlasView != null) atlasView.close(); if (atlas != null) atlas.close();
        if (sceneView != null) sceneView.close(); if (sceneCopy != null) sceneCopy.close();
        if (lightingView != null) lightingView.close(); if (lighting != null) lighting.close();
        if (tile != null) tile.close();
        if (directView != null) directView.close(); if (directLights != null) directLights.close();
        if (directPixels != null) directPixels.close();
        directView = null; directLights = null; directPixels = null; selectedVolume = null; selectedLights = java.util.List.of();
        TotemLumenClient.LOGGER.info("Raster direct lights retired: selections={}, selectionMs={}, uploadBytes={}", lightSelections, lightSelectionNanos / 1_000_000.0, lightUploadBytes);
        lightSelections = lightSelectionNanos = lightUploadBytes = 0;
        atlas = sceneCopy = lighting = null; atlasView = sceneView = lightingView = null; tile = null;
        java.util.Arrays.fill(uploaded, null); epoch = -1; logged = false;
        TotemLumenClient.LOGGER.info("Raster ray resources retired: frames={}, uploadBytes={}", frames, uploadBytes);
        frames = uploadBytes = 0;
    }
}
