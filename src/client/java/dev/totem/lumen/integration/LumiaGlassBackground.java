package dev.totem.lumen.integration;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.totem.lumen.TotemLumenClient;

/** Sampled copies of the world attachments after the opaque pass finishes. */
public final class LumiaGlassBackground {
    private static final String COLOR = "LumiaOpaqueColor";
    private static final String DEPTH = "LumiaOpaqueDepth";
    private static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
            .withUniform(COLOR, UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform(DEPTH, UniformType.COMBINED_IMAGE_SAMPLER).build();
    private static GpuDevice device;
    private static GpuTexture color, depth;
    private static GpuTextureView colorView, depthView;
    private static boolean captured, failureLogged;

    private LumiaGlassBackground() {}

    public static BindGroupLayout layout() { return LAYOUT; }

    public static boolean ready() { return captured && LumiaScene.ready(); }

    public static void endTransparency() { captured = false; }

    public static void bind(RenderPass pass) {
        var samplers = RenderSystem.getSamplerCache();
        pass.setUniform(COLOR, colorView, samplers.getClampToEdge(FilterMode.LINEAR));
        pass.setUniform(DEPTH, depthView, samplers.getClampToEdge(FilterMode.NEAREST));
    }

    public static void capture(RenderTarget target) {
        captured = false;
        if (!LumiaScene.ready()) return;
        var srcColor = target.getColorTexture();
        var srcDepth = target.getDepthTexture();
        if (srcColor == null || srcDepth == null) return;
        int width = srcColor.getWidth(0);
        int height = srcColor.getHeight(0);
        if (width <= 0 || height <= 0 || width != srcDepth.getWidth(0) || height != srcDepth.getHeight(0)) return;

        try {
            var gpu = RenderSystem.getDevice();
            if (device != gpu || color == null || color.getWidth(0) != width || color.getHeight(0) != height
                    || color.getFormat() != srcColor.getFormat() || depth.getFormat() != srcDepth.getFormat()) {
                close();
                device = gpu;
                int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;
                color = gpu.createTexture("Lumia opaque color", usage, srcColor.getFormat(), width, height, 1, 1);
                colorView = gpu.createTextureView(color);
                depth = gpu.createTexture("Lumia opaque depth", usage, srcDepth.getFormat(), width, height, 1, 1);
                depthView = gpu.createTextureView(depth);
                TotemLumenClient.LOGGER.info("Lumia glass background allocated at {}x{} ({}, {})",
                        width, height, srcColor.getFormat(), srcDepth.getFormat());
            }
            // RenderPearl records both copies and the next draw on the same device encoder.
            // VulkanCommandEncoder inserts a memory barrier after each image copy.
            var encoder = gpu.createCommandEncoder();
            encoder.copyTextureToTexture(srcColor, color, 0, 0, 0, 0, 0, width, height);
            encoder.copyTextureToTexture(srcDepth, depth, 0, 0, 0, 0, 0, width, height);
            captured = true;
            failureLogged = false;
        } catch (RuntimeException ex) {
            close();
            if (!failureLogged) TotemLumenClient.LOGGER.warn("Lumia glass background capture failed", ex);
            failureLogged = true;
        }
    }

    public static void close() {
        captured = false;
        if (colorView != null) colorView.close();
        if (depthView != null) depthView.close();
        if (color != null) color.close();
        if (depth != null) depth.close();
        colorView = depthView = null;
        color = depth = null;
        device = null;
    }
}
