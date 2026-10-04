package dev.totem.lumen.integration;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.render.HybridTerrainPolicy;
import dev.totem.lumen.scene.FrameSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Optional;

/** Experimental near-lighting composite over Minecraft's complete Vulkan world render. */
public final class HybridTerrainRenderer {
    private static boolean unavailable;
    private static boolean logged;
    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("totem-lumen", "pipeline/hybrid_terrain"))
            .withVertexShader(RenderPipelines.TRACY_BLIT.getShaders().get(ShaderType.VERTEX))
            .withFragmentShader(Identifier.fromNamespaceAndPath("totem-lumen", "core/hybrid_terrain"))
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("TotemSampler", UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.of(new BlendFunction(
                    BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA,
                    BlendFactor.ZERO, BlendFactor.ONE)),
                    RenderPipelines.DEBUG_FILLED_BOX.getColorTargetStates().getFirst().format(),
                    ColorTargetState.WRITE_ALL))
            .build();

    private HybridTerrainRenderer() { }

    public static void render(CameraRenderState camera, GpuTextureView output, FrameSnapshot frame,
                              float[][] basis, int width, int height, int rayDistance) {
        Minecraft client = Minecraft.getInstance();
        if (unavailable || camera == null || !camera.initialized || client.level == null
                || !client.level.dimension().identifier().toString().equals(frame.dimensionId())) return;
        var target = client.gameRenderer.mainRenderTarget();
        if (target == null || target.getColorTextureView() == null || target.getDepthTextureView() == null) return;
        CompiledRenderPipeline pipeline;
        try {
            pipeline = RenderSystem.getCompiledPipeline(PIPELINE);
        } catch (IllegalStateException failure) {
            unavailable = true;
            TotemLumenClient.LOGGER.error("Hybrid terrain compositor unavailable; retaining Minecraft terrain", failure);
            return;
        }
        // Reconstruct relative to the current camera to avoid introducing additional far-world
        // precision loss. Reproject into the camera belonging to the queued RT color/depth pair.
        Matrix4f inverse = new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix).invert();
        Matrix4f metadata = new Matrix4f();
        metadata.setColumn(0, new Vector4f(basis[0][0], basis[0][1], basis[0][2],
                (float) Math.tan(Math.toRadians(frame.fovDegrees()) * 0.5)));
        metadata.setColumn(1, new Vector4f(basis[1][0], basis[1][1], basis[1][2], width / (float) height));
        metadata.setColumn(2, new Vector4f(basis[2][0], basis[2][1], basis[2][2], rayDistance));
        metadata.setColumn(3, new Vector4f((float) (camera.pos.x - frame.cameraX()),
                (float) (camera.pos.y - frame.cameraY()), (float) (camera.pos.z - frame.cameraZ()),
                RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1 : 0));
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        try (var pass = encoder.createRenderPass(() -> "Totem Lumen near lighting over complete terrain",
                target.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(pipeline);
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.setUniform("DepthSampler", target.getDepthTextureView(), sampler);
            pass.setUniform("TotemSampler", output, sampler);
            pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(
                    inverse, new Vector4f(HybridTerrainPolicy.FADE_START, 0, 0, 0), new Vector3f(), metadata));
            pass.draw(3, 1, 0, 0);
        }
        encoder.submit();
        if (!logged) {
            logged = true;
            TotemLumenClient.LOGGER.info("Hybrid terrain ACTIVE (experimental): vanilla terrain retained; "
                    + "depth-validated near lighting, render={}x{}, rayDistance={}", width, height, rayDistance);
        }
    }
}
