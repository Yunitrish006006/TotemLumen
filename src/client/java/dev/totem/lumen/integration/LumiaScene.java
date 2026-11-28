package dev.totem.lumen.integration;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.*;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.render.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.SheepRenderState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.MapColor;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.List;

/** Read-only scene bindings shared by native geometry draws. Upload once before the world passes. */
public final class LumiaScene {
    private static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
            .withUniform("LumiaEnvironment", UniformType.UNIFORM_BUFFER)
            .withUniform("LumiaMovingActors", UniformType.UNIFORM_BUFFER)
            .withUniform("LumiaVoxels", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("LumiaLights", UniformType.COMBINED_IMAGE_SAMPLER).build();
    private static final int PALETTE_START = RasterDirectLights.TEXELS;
    private static final int MAPPING_START = PALETTE_START + 64;
    private static final int GLASS_START = MAPPING_START + RasterLightingVolume.SLOTS;
    private static final int METADATA_WIDTH = GLASS_START + LumiaGlassMaterials.TABLE_TEXELS;
    private static long glassRevision = -1;
    private static final LumiaVoxelAtlas atlas = new LumiaVoxelAtlas();
    private static GpuDevice device;
    private static GpuTexture voxels, lights;
    private static GpuTextureView voxelView, lightView;
    private static NativeImage tile, lightPixels;
    private static GpuBufferSlice environment, actors;
    private static RasterLightingVolume lastVolume;
    private static List<RasterDirectLights.Light> lastLights;
    private static int lastX, lastY, lastZ;
    private static boolean failed, logged;

    private LumiaScene() {}
    public static BindGroupLayout layout() { return LAYOUT; }
    public static boolean ready() { return environment != null && RendererSettings.lumiaEnabled(); }

    public static boolean failed() { return failed; }

    public static void bind(RenderPass pass) {
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        pass.setUniform("LumiaEnvironment", environment);
        pass.setUniform("LumiaMovingActors", actors);
        pass.setUniform("LumiaVoxels", voxelView, sampler);
        pass.setUniform("LumiaLights", lightView, sampler);
    }

    public static void beginFrame(CameraRenderState camera) {
        environment = null;
        if (!RendererSettings.lumiaEnabled() || failed || camera == null || !camera.initialized) return;
        var client = Minecraft.getInstance();
        var volume = RasterLightingScene.snapshot();
        if (client.level == null || volume == null) return;
        try {
            var gpu = RenderSystem.getDevice();
            if (device != gpu) {
                close();
                if (!BackendProbe.detect().vulkan()) {
                    failed = true;
                    TotemLumenClient.LOGGER.warn("Lumia requires the Vulkan backend");
                    return;
                }
                allocate(gpu);
            }
            var encoder = gpu.createCommandEncoder();
            boolean mappingChanged = atlas.update(volume, (slot, section) -> {
                for (int i = 0; i < 4096; i++) tile.setPixelABGR(i & 15, i >>> 4, section.voxel(i));
                encoder.writeToTexture(voxels, tile, 0, 0, RasterLightingVolume.tileX(slot), RasterLightingVolume.tileY(slot));
            });
            boolean metadataChanged = mappingChanged || lastVolume == null;
            if (metadataChanged) {
                for (int slot = 0; slot < RasterLightingVolume.SLOTS; slot++) {
                    lightPixels.setPixelABGR(MAPPING_START + slot, 0, 0xff000000 | atlas.entry(slot));
                }
            }
            BlockMaterialRuleRegistry.ensureLoaded(client.getResourceManager());
            long revision = BlockMaterialRuleRegistry.revision();
            if (glassRevision != revision) {
                LumiaGlassMaterials.writePalette(lightPixels, GLASS_START);
                glassRevision = revision;
                metadataChanged = true;
            }
            Vector3f offset = new Vector3f((float)(camera.pos.x - volume.x), (float)(camera.pos.y - volume.y),
                    (float)(camera.pos.z - volume.z));
            int x = (int)Math.floor(offset.x), y = (int)Math.floor(offset.y), z = (int)Math.floor(offset.z);
            if (lastVolume != volume || lastX != x || lastY != y || lastZ != z) {
                var selected = RasterDirectLights.select(volume, x + .5, y + .5, z + .5);
                if (!selected.equals(lastLights)) {
                    for (int i = 0; i < RasterDirectLights.MAX_LIGHTS; i++) {
                        lightPixels.setPixelABGR(i * 2, 0, i < selected.size() ? selected.get(i).positionTexel() : 0);
                        lightPixels.setPixelABGR(i * 2 + 1, 0, i < selected.size() ? selected.get(i).abgr() : 0);
                    }
                    metadataChanged = true;
                }
                lastLights = selected;
                lastVolume = volume;
                lastX = x; lastY = y; lastZ = z;
            }
            if (metadataChanged) encoder.writeToTexture(lights, lightPixels, 0, 0, 0, 0);
            encoder.submit();

            var frame = client.gameRenderer.gameRenderState().levelRenderState;
            var skyState = frame.skyRenderState;
            // The sky renderer rotates +Y by sunAngle about X, then -90 degrees about Y.
            float elevation = (float) Math.cos(skyState.sunAngle);
            boolean overworldSky = skyState.skybox == DimensionType.Skybox.OVERWORLD;
            float daylight = overworldSky ? Math.clamp(elevation * 3f, 0, 1)
                    * (.35f + .65f * skyState.rainBrightness) : 0;
            var sky = overworldSky ? new Vector4f(.05f + .35f * daylight, .065f + .49f * daylight,
                    .13f + .62f * daylight, daylight)
                    : skyState.skybox == DimensionType.Skybox.END ? new Vector4f(.10f,.075f,.16f,0)
                    : new Vector4f(.20f,.065f,.035f,0);
            var sun = new Vector4f(-(float) Math.sin(skyState.sunAngle), elevation, 0, 0);
            var held = new Matrix4f().zero();
            var hands = ClientHeldLightState.captured();
            int handCount = Math.min(2, hands.size());
            for (int i = 0; i < handCount; i++) {
                var light = hands.get(i);
                held.setColumn(i * 2, new Vector4f((float) (light.x() - volume.x),
                        (float) (light.y() - volume.y), (float) (light.z() - volume.z), light.radius() / 1.2f));
                held.setColumn(i * 2 + 1, new Vector4f(light.red(), light.green(), light.blue(), 0));
            }
            sun.w = handCount;
            actors = actorBounds(frame.entityRenderStates, volume);
            environment = RenderSystem.getDynamicUniforms().writeTransform(held,
                    sky, offset, new Matrix4f().zero().setColumn(0, sun));
            if (!logged) {
                TotemLumenClient.LOGGER.info("Lumia Vulkan scene bindings ready");
                logged = true;
            }
        } catch (RuntimeException ex) {
            close();
            failed = true;
            TotemLumenClient.LOGGER.error("Lumia scene preparation failed", ex);
        }
    }

    /** Only the nearest four visible living entities contribute secondary-ray proxies (32 blocks). */
    private static GpuBufferSlice actorBounds(List<EntityRenderState> states, RasterLightingVolume volume) {
        LivingEntityRenderState[] nearest = new LivingEntityRenderState[4];
        int count = 0;
        for (var state : states) {
            if (!(state instanceof LivingEntityRenderState living) || state.isInvisible || living.isInvisibleToPlayer
                    || living instanceof AvatarRenderState avatar && avatar.isSpectator
                    || state.boundingBoxWidth <= .01f || state.boundingBoxHeight <= .01f
                    || state.distanceToCameraSq > 32 * 32) continue;
            int i = count;
            while (i > 0 && nearest[i - 1].distanceToCameraSq > state.distanceToCameraSq) {
                if (i < nearest.length) nearest[i] = nearest[i - 1];
                i--;
            }
            if (i < nearest.length) {
                nearest[i] = living;
                if (count < nearest.length) count++;
            }
        }
        var centers = new Matrix4f().zero();
        var radii = new Matrix4f().zero();
        for (int i = 0; i < count; i++) {
            var e = nearest[i];
            float width = e.boundingBoxWidth * .5f, height = e.boundingBoxHeight * .5f;
            int tint = e.hasRedOverlay ? 0xce5252 : e.isFullyFrozen ? 0xb7d9e8
                    : e instanceof SheepRenderState sheep && !sheep.isSheared ? sheep.getWoolColor() : 0x93887d;
            centers.setColumn(i, new Vector4f((float) (e.x - volume.x), (float) (e.y - volume.y) + height,
                    (float) (e.z - volume.z), tint & 0xffffff));
            radii.setColumn(i, new Vector4f(1 / width, 1 / height, 1 / width, 0));
        }
        return RenderSystem.getDynamicUniforms().writeTransform(centers,
                new Vector4f(count, 0, 0, 0), new Vector3f(), radii);
    }

    private static void allocate(GpuDevice gpu) {
        device = gpu;
        int flags = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
        voxels = gpu.createTexture("Lumia occlusion and emission", flags,
                GpuFormat.RGBA8_UNORM, RasterLightingVolume.ATLAS_WIDTH, RasterLightingVolume.ATLAS_HEIGHT, 1, 1);
        voxelView = gpu.createTextureView(voxels);
        tile = new NativeImage(16, 256, false);
        lights = gpu.createTexture("Lumia emitters and reflection colours", flags,
                GpuFormat.RGBA8_UNORM, METADATA_WIDTH, 1, 1, 1);
        lightView = gpu.createTextureView(lights);
        lightPixels = new NativeImage(METADATA_WIDTH, 1, false);
        for (int i = 0; i < 64; i++) {
            var color = MapColor.byId(i);
            lightPixels.setPixel(PALETTE_START + i, 0, 0xff000000 | (color == null || color == MapColor.NONE ? 0x777777 : color.col));
        }
    }

    public static void close() {
        LumiaGlassBackground.close();
        glassRevision = -1;
        environment = actors = null;
        if (voxelView != null) voxelView.close();
        if (lightView != null) lightView.close();
        if (voxels != null) voxels.close();
        if (lights != null) lights.close();
        if (tile != null) tile.close();
        if (lightPixels != null) lightPixels.close();
        voxelView = lightView = null;
        voxels = lights = null;
        tile = lightPixels = null;
        device = null;
        lastVolume = null;
        lastLights = null;
        atlas.clear();
        logged = failed = false;
    }
}
