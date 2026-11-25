package dev.totem.lumen.integration;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.render.BackendProbe;
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

/**
 * Owned SURFACE_CAPTURE stage for the raster-primary path.
 *
 * <p>It never replays Minecraft chunk draw lists or retains their vertex/index buffers. The
 * stage copies completed native scene color, captures depth at R32 precision and reconstructs
 * a full-resolution normal field into Totem-owned textures.</p>
 */
public final class RasterSurfaceCapture {
    private static final long MAX_PIXELS = 4096L * 2160;

    private static final BindGroupLayout DEPTH_SAMPLER = BindGroupLayout.builder()
            .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .build();

    private static final RenderPipeline DEPTH_CAPTURE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("totem-lumen", "pipeline/raster_surface_depth"))
            .withVertexShader(RenderPipelines.TRACY_BLIT.getShaders().get(ShaderType.VERTEX))
            .withFragmentShader(Identifier.fromNamespaceAndPath("totem-lumen", "core/raster_surface_depth"))
            .withBindGroupLayout(DEPTH_SAMPLER)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(
                    Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
            .build();

    private static final RenderPipeline NORMAL_CAPTURE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("totem-lumen", "pipeline/raster_surface_normal"))
            .withVertexShader(RenderPipelines.TRACY_BLIT.getShaders().get(ShaderType.VERTEX))
            .withFragmentShader(Identifier.fromNamespaceAndPath("totem-lumen", "core/raster_surface_normal"))
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(DEPTH_SAMPLER)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(
                    Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .build();

    private static GpuDevice device;
    private static GpuTexture color, depth, normal;
    private static GpuTextureView colorView, depthView, normalView;
    private static long frameSerial, frames;
    private static boolean failed, logged;

    private RasterSurfaceCapture() { }

    public static boolean unavailable() { return failed; }

    public static RasterSurfaceFrame capture(CameraRenderState camera) {
        if (!RendererSettings.rasterLightingEnabled() || failed || camera == null || !camera.initialized) {
            return null;
        }
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (target == null || target.getColorTexture() == null || target.getDepthTextureView() == null) {
            return null;
        }
        if (!BackendProbe.detect().vulkan()) return null;

        var nativeColor = target.getColorTexture();
        int width = nativeColor.getWidth(0), height = nativeColor.getHeight(0);
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) return null;

        try {
            var gpu = RenderSystem.getDevice();
            ensureTargets(gpu, nativeColor, width, height);

            var encoder = gpu.createCommandEncoder();
            encoder.copyTextureToTexture(nativeColor, color, 0, 0, 0, 0, 0, width, height);

            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            var depthPipeline = RenderSystem.getCompiledPipeline(DEPTH_CAPTURE);
            try (var pass = encoder.createRenderPass(
                    () -> "Totem SURFACE_CAPTURE depth", depthView, Optional.empty())) {
                pass.setPipeline(depthPipeline);
                pass.setUniform("DepthSampler", target.getDepthTextureView(), nearest);
                pass.draw(3, 1, 0, 0);
            }

            Matrix4f inverse = new Matrix4f(camera.projectionMatrix)
                    .mul(camera.viewRotationMatrix)
                    .invert();
            Matrix4f metadata = new Matrix4f().zero()
                    .setColumn(0, new Vector4f(
                            0, 0, gpu.getDeviceInfo().isZZeroToOne() ? 1 : 0, 0));
            var uniforms = RenderSystem.getDynamicUniforms()
                    .writeTransform(inverse, new Vector4f(1), new Vector3f(), metadata);
            var normalPipeline = RenderSystem.getCompiledPipeline(NORMAL_CAPTURE);
            try (var pass = encoder.createRenderPass(
                    () -> "Totem SURFACE_CAPTURE normal", normalView, Optional.empty())) {
                pass.setPipeline(normalPipeline);
                pass.setUniform("DynamicTransforms", uniforms);
                pass.setUniform("DepthSampler", target.getDepthTextureView(), nearest);
                pass.draw(3, 1, 0, 0);
            }
            encoder.submit();

            frameSerial = frameSerial == Long.MAX_VALUE ? 1 : frameSerial + 1;
            frames++;
            if (!logged) {
                logged = true;
                TotemLumenClient.LOGGER.info(
                        "RASTER SURFACE_CAPTURE ACTIVE: {}x{}, ownedColor=true, ownedDepth=R32, ownedNormal=RGBA16F, colorSemantic={}",
                        width, height, RasterSurfaceFrame.ColorSemantic.NATIVE_LIT_COLOR);
            }
            return new RasterSurfaceFrame(
                    gpu,
                    width,
                    height,
                    frameSerial,
                    RasterSurfaceFrame.ColorSemantic.NATIVE_LIT_COLOR,
                    colorView,
                    depthView,
                    normalView
            );
        } catch (RuntimeException failure) {
            failed = true;
            TotemLumenClient.LOGGER.error(
                    "Raster surface capture disabled; retaining Minecraft rendering", failure);
            close();
            return null;
        }
    }

    private static void ensureTargets(GpuDevice gpu, GpuTexture nativeColor, int width, int height) {
        if (device == gpu
                && color != null
                && color.getWidth(0) == width
                && color.getHeight(0) == height
                && color.getFormat() == nativeColor.getFormat()
                && depth != null
                && depth.getWidth(0) == width
                && depth.getHeight(0) == height
                && normal != null
                && normal.getWidth(0) == width
                && normal.getHeight(0) == height) {
            return;
        }

        close();
        device = gpu;
        color = gpu.createTexture(
                "Totem raster owned scene color",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                nativeColor.getFormat(),
                width, height, 1, 1);
        depth = gpu.createTexture(
                "Totem raster owned surface depth",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.R32_FLOAT,
                width, height, 1, 1);
        normal = gpu.createTexture(
                "Totem raster owned surface normal",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA16_FLOAT,
                width, height, 1, 1);
        colorView = gpu.createTextureView(color);
        depthView = gpu.createTextureView(depth);
        normalView = gpu.createTextureView(normal);
    }

    public static void tickLifecycle(Minecraft client) {
        if (!RendererSettings.rasterLightingEnabled() || client.level == null) close();
    }

    public static void close() {
        if (colorView != null) colorView.close();
        if (depthView != null) depthView.close();
        if (normalView != null) normalView.close();
        if (color != null) color.close();
        if (depth != null) depth.close();
        if (normal != null) normal.close();
        colorView = depthView = normalView = null;
        color = depth = normal = null;
        device = null;
        if (logged) {
            TotemLumenClient.LOGGER.info("Raster surface resources retired: frames={}", frames);
        }
        frames = 0;
        logged = false;
    }
}
