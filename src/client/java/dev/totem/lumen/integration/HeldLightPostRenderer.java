package dev.totem.lumen.integration;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.device.GpuDevice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import dev.totem.lumen.render.RendererSettings;
import dev.totem.lumen.gameplay.light.RgbLightAttenuation;
import dev.totem.lumen.TotemLumenClient;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.List;
import java.util.Optional;

/** Depth-reconstructed moving light for both vanilla-world profiles; no terrain remeshing. */
public final class HeldLightPostRenderer {
    private static final int MAX_VISIBLE_LIGHTS = 4;
    private static final float RGB_HELD_RADIUS_SCALE = 0.9f;
    private static final float RGB_HELD_STRENGTH = 0.90f;
    private static RendererSettings.RenderProfile lastLoggedProfile;
    private static boolean pipelineUnavailable;
    private static GpuDevice snapshotDevice;
    private static GpuTexture sceneSnapshot;
    private static GpuTextureView sceneSnapshotView;
    private static final RenderPipeline PURE_PIPELINE = pipeline("held_light_pure", BlendFunction.ADDITIVE, false);
    /** Add a reflectance-weighted contribution computed from a pre-light scene snapshot. */
    private static final RenderPipeline RGB_PIPELINE = pipeline("held_light_rgb", new BlendFunction(
            BlendFactor.ONE, BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ONE
    ), true);

    private HeldLightPostRenderer() {
    }

    public static void render(CameraRenderState camera) {
        if (pipelineUnavailable
                || RendererSettings.renderProfile() == RendererSettings.RenderProfile.TOTEM_LUMEN
                || !RenderSystem.isOnRenderThread()) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || camera == null || !camera.initialized) return;
        List<ClientHeldLightState.Light> lights = ClientHeldLightState.captured();
        if (lights.isEmpty()) return;

        RenderTarget target = client.gameRenderer.mainRenderTarget();
        if (target == null || target.getColorTextureView() == null
                || target.getDepthTextureView() == null) return;

        Matrix4f view = new Matrix4f(camera.viewRotationMatrix).translate(
                (float) -camera.pos.x, (float) -camera.pos.y, (float) -camera.pos.z
        );
        Matrix4f inverseViewProjection = new Matrix4f(camera.projectionMatrix).mul(view).invert();
        boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        RendererSettings.RenderProfile profile = RendererSettings.renderProfile();
        boolean rgbProfile = profile == RendererSettings.RenderProfile.MINECRAFT_RGB;
        var compiledPipeline = compilePipelineOrDisable(rgbProfile ? RGB_PIPELINE : PURE_PIPELINE);
        if (compiledPipeline == null) return;
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        if (rgbProfile) {
            GpuTexture color = target.getColorTexture();
            if (color == null || (color.usage() & GpuTexture.USAGE_COPY_SRC) == 0) return;
            HeldLightFieldAtlas.update(encoder, lights, client.level.dimension().identifier().toString());
            ensureSnapshot(color, target.width, target.height);
            // Sample only the scene before any held lights. Reading the attachment being written
            // would be undefined, and copying once lets all nearby lights share the same albedo.
            encoder.copyTextureToTexture(color, sceneSnapshot, 0, 0, 0, 0, 0,
                    target.width, target.height);
        }
        try (RenderPass pass = encoder.createRenderPass(
                () -> "Totem Lumen held light",
                target.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(compiledPipeline);
            pass.setUniform("DepthSampler", target.getDepthTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            if (rgbProfile) {
                pass.setUniform("SceneColorSampler", sceneSnapshotView,
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.setUniform("PlacedRgbSampler", HeldLightFieldAtlas.view(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            }
            int count = 0;
            for (ClientHeldLightState.Light light : lights) {
                if (count++ >= MAX_VISIBLE_LIGHTS) break;
                Matrix4f metadata = new Matrix4f();
                metadata.setColumn(0, new Vector4f(
                        light.radius() * (rgbProfile ? RGB_HELD_RADIUS_SCALE : 1.0f),
                        zeroToOne ? 1.0f : 0.0f, 0.0f, RgbLightAttenuation.RADIAL_DISTANCE_SCALE
                ));
                if (rgbProfile) {
                    Vector3f baseline = VanillaRgbLighting.skyAndAmbientAt(
                            smoothedSkyLevel(client.level, light.x(), light.y(), light.z())
                    );
                    metadata.setColumn(1, new Vector4f(baseline, 1.0f));
                    int slot = count - 1;
                    metadata.setColumn(2, new Vector4f(
                            HeldLightFieldAtlas.originX(slot), HeldLightFieldAtlas.originY(slot),
                            HeldLightFieldAtlas.originZ(slot), slot
                    ));
                }

                pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(
                        inverseViewProjection,
                        new Vector4f(light.red(), light.green(), light.blue(), rgbProfile ? RGB_HELD_STRENGTH : 1.35f),
                        new Vector3f(light.x(), light.y(), light.z()),
                        metadata
                ));
                pass.draw(3, 1, 0, 0);
            }
        }
        encoder.submit();
        if (profile != lastLoggedProfile) {
            lastLoggedProfile = profile;
            TotemLumenClient.LOGGER.info("Held light surface pass active: profile={}, sources={}",
                    profile, Math.min(lights.size(), MAX_VISIBLE_LIGHTS));
        }
    }

    /** Keep the handheld reflectance baseline continuous as a player crosses sky-light cells. */
    private static float smoothedSkyLevel(ClientLevel level, float x, float y, float z) {
        float sampleX = x - 0.5f;
        float sampleY = y - 0.5f;
        float sampleZ = z - 0.5f;
        int baseX = (int) Math.floor(sampleX);
        int baseY = (int) Math.floor(sampleY);
        int baseZ = (int) Math.floor(sampleZ);
        float fx = sampleX - baseX;
        float fy = sampleY - baseY;
        float fz = sampleZ - baseZ;
        float sky = 0.0f;
        BlockPos.MutableBlockPos sample = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= 1; dy++) {
            for (int dz = 0; dz <= 1; dz++) {
                for (int dx = 0; dx <= 1; dx++) {
                    float weight = (dx == 0 ? 1.0f - fx : fx)
                            * (dy == 0 ? 1.0f - fy : fy)
                            * (dz == 0 ? 1.0f - fz : fz);
                    sample.set(baseX + dx, baseY + dy, baseZ + dz);
                    sky += weight * level.getBrightness(LightLayer.SKY, sample);
                }
            }
        }
        return sky;
    }

    private static void ensureSnapshot(GpuTexture color, int width, int height) {
        GpuDevice device = RenderSystem.getDevice();
        if (sceneSnapshot != null && (snapshotDevice != device || sceneSnapshot.isClosed()
                || sceneSnapshot.getWidth(0) != width || sceneSnapshot.getHeight(0) != height
                || sceneSnapshot.getFormat() != color.getFormat())) {
            sceneSnapshotView.close();
            sceneSnapshot.close();
            sceneSnapshotView = null;
            sceneSnapshot = null;
        }
        if (sceneSnapshot == null) {
            sceneSnapshot = device.createTexture("Totem Lumen held-light scene snapshot",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    color.getFormat(), width, height, 1, 1);
            sceneSnapshotView = device.createTextureView(sceneSnapshot);
            snapshotDevice = device;
        }
    }

    private static RenderPipeline pipeline(String name, BlendFunction blend, boolean sampleScene) {
        var builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("totem-lumen", "pipeline/" + name))
                .withVertexShader(RenderPipelines.TRACY_BLIT.getShaders().get(ShaderType.VERTEX))
                .withFragmentShader(Identifier.fromNamespaceAndPath("totem-lumen",
                        sampleScene ? "core/held_light_rgb" : "core/held_light"))
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(new ColorTargetState(
                        Optional.of(blend),
                        RenderPipelines.DEBUG_FILLED_BOX.getColorTargetStates().getFirst().format(),
                        ColorTargetState.WRITE_ALL
                ));
        var samplers = BindGroupLayout.builder()
                .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER);
        if (sampleScene) samplers.withUniform("SceneColorSampler", UniformType.COMBINED_IMAGE_SAMPLER);
        if (sampleScene) samplers.withUniform("PlacedRgbSampler", UniformType.COMBINED_IMAGE_SAMPLER);
        return builder.withBindGroupLayout(samplers.build()).build();
    }

    private static com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline compilePipelineOrDisable(
            RenderPipeline pipeline
    ) {
        try {
            return RenderSystem.getCompiledPipeline(pipeline);
        } catch (IllegalStateException error) {
            pipelineUnavailable = true;
            TotemLumenClient.LOGGER.error("Held light shader unavailable; disabling held-light pass for this client session", error);
            return null;
        }
    }
}
