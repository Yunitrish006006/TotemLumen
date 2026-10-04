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
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Owned SURFACE_CAPTURE stage for the raster-primary path.
 *
 * <p>It never replays Minecraft chunk draw lists or retains their vertex/index buffers. The
 * stage copies the completed native scene color into a Totem-owned texture and samples native
 * depth into a Totem-owned color texture. Downstream stages only receive those owned views.</p>
 */
public final class RasterSurfaceCapture {
    private static final long MAX_PIXELS = 4096L * 2160;
    private static final RenderPipeline DEPTH_CAPTURE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("totem-lumen", "pipeline/raster_surface_depth"))
            .withVertexShader(RenderPipelines.TRACY_BLIT.getShaders().get(ShaderType.VERTEX))
            .withFragmentShader(Identifier.fromNamespaceAndPath("totem-lumen", "core/raster_surface_depth"))
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(
                    Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
            .build();

    private static GpuDevice device;
    private static GpuTexture color, depth;
    private static GpuTextureView colorView, depthView;
    private static long frameSerial, frames;
    private static boolean failed, logged;

    private RasterSurfaceCapture() { }

    public static RasterSurfaceFrame capture() {
        if (!RendererSettings.rasterLightingEnabled() || failed) return null;
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (target == null || target.getColorTexture() == null || target.getDepthTextureView() == null) return null;
        if (!BackendProbe.detect().vulkan()) return null;

        var nativeColor = target.getColorTexture();
        int width = nativeColor.getWidth(0), height = nativeColor.getHeight(0);
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) return null;

        try {
            var gpu = RenderSystem.getDevice();
            ensureTargets(gpu, nativeColor, width, height);

            var encoder = gpu.createCommandEncoder();
            encoder.copyTextureToTexture(nativeColor, color, 0, 0, 0, 0, 0, width, height);

            var compiled = RenderSystem.getCompiledPipeline(DEPTH_CAPTURE);
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            try (var pass = encoder.createRenderPass(
                    () -> "Totem raster surface depth capture", depthView, Optional.empty())) {
                pass.setPipeline(compiled);
                pass.setUniform("DepthSampler", target.getDepthTextureView(), nearest);
                pass.draw(3, 1, 0, 0);
            }
            encoder.submit();

            frameSerial = frameSerial == Long.MAX_VALUE ? 1 : frameSerial + 1;
            frames++;
            if (!logged) {
                logged = true;
                TotemLumenClient.LOGGER.info(
                        "RASTER SURFACE_CAPTURE ACTIVE: {}x{}, ownedColor=true, ownedDepth=true, colorSemantic={}",
                        width, height, RasterSurfaceFrame.ColorSemantic.NATIVE_LIT_COLOR);
            }
            return new RasterSurfaceFrame(
                    gpu,
                    width,
                    height,
                    frameSerial,
                    RasterSurfaceFrame.ColorSemantic.NATIVE_LIT_COLOR,
                    colorView,
                    depthView
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
                && depth.getHeight(0) == height) {
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
        colorView = gpu.createTextureView(color);
        depthView = gpu.createTextureView(depth);
    }

    public static void tickLifecycle(Minecraft client) {
        if (!RendererSettings.rasterLightingEnabled() || client.level == null) close();
    }

    public static void close() {
        if (colorView != null) colorView.close();
        if (depthView != null) depthView.close();
        if (color != null) color.close();
        if (depth != null) depth.close();
        colorView = depthView = null;
        color = depth = null;
        device = null;
        if (logged) {
            TotemLumenClient.LOGGER.info("Raster surface resources retired: frames={}", frames);
        }
        frames = 0;
        logged = false;
    }
}
