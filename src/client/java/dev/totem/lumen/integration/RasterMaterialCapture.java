package dev.totem.lumen.integration;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.*;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.mixin.RasterMaterialLayersInvoker;
import dev.totem.lumen.render.RendererSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Developer-only opaque/cutout base-colour capture. NOT a complete albedo G-buffer:
 * native vertex colour still includes baked AO/directional shade as well as tint.
 * No vanilla pipeline, lightmap, mesh or gameplay-light state is modified.
 */
public final class RasterMaterialCapture {
    private static final boolean ENABLED = Boolean.getBoolean("totem.lumen.rasterMaterialCapture");
    private static final boolean PREVIEW = Boolean.getBoolean("totem.lumen.rasterMaterialPreview");
    private static final boolean RGB_PREVIEW = Boolean.getBoolean("totem.lumen.rasterMaterialLighting");
    private static final long MAX_PIXELS = 4096L * 2160;
    private static final Map<RenderPipeline, RenderPipeline> PIPELINES = new IdentityHashMap<>();
    private static ChunkSectionsToRender sections;
    private static GpuSampler sampler;
    private static GpuTextureView atlas;
    private static GpuBufferSlice projection;
    private static GpuDevice device;
    private static GpuTexture color, depth;
    private static GpuTextureView colorView, depthView;
    private static boolean failed, logged, capturedThisFrame;
    private static long frames;

    private RasterMaterialCapture() { }

    private static boolean enabled() { return ENABLED && !failed && RendererSettings.rasterLightingEnabled(); }

    public static void beginFrame() { capturedThisFrame = false; clearPending(); }

    /** RGB takes precedence over raw preview; neither flag can enable capture by itself. */
    public static boolean lightingPreviewRequested() { return ENABLED && RGB_PREVIEW; }
    public static boolean rawPreviewRequested() { return ENABLED && PREVIEW && !RGB_PREVIEW; }

    /** Borrowed for this frame only. Never consume a prior frame after a skipped/failed capture. */
    public record Frame(GpuTextureView color, GpuTextureView depth) { }

    public static Frame lightingFrame(GpuDevice gpu, int width, int height) {
        if (!lightingPreviewRequested() || !enabled() || !capturedThisFrame || device != gpu
                || color == null || color.getWidth(0) != width || color.getHeight(0) != height) return null;
        return new Frame(colorView, depthView);
    }

    public static void remember(ChunkSectionsToRender draws, GpuSampler drawSampler, GpuTextureView blockAtlas) {
        if (!enabled()) return;
        // One native opaque group per LevelRenderer render; references never survive the frame.
        sections = draws; sampler = drawSampler; atlas = blockAtlas;
        projection = RenderSystem.getProjectionMatrixBuffer();
    }

    /** Runs after native render passes close; never nests passes or re-enters renderGroup. */
    public static void render() {
        if (!enabled() || sections == null) { clearPending(); return; }
        try {
            var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            if (target == null || target.getColorTexture() == null || target.getDepthTexture() == null) return;
            var original = target.getColorTexture();
            int w = original.getWidth(0), h = original.getHeight(0);
            if ((long) w * h > MAX_PIXELS) return;
            var gpu = RenderSystem.getDevice();
            if (device != gpu || color == null || color.getWidth(0) != w || color.getHeight(0) != h
                    || color.getFormat() != original.getFormat() || depth.getFormat() != target.getDepthTexture().getFormat()) {
                retireTargets(); device = gpu;
                color = gpu.createTexture("Raster base colour (baked vertex shade retained)",
                        GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
                        original.getFormat(), w, h, 1, 1);
                depth = gpu.createTexture("Raster material depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                        target.getDepthTexture().getFormat(), w, h, 1, 1);
                colorView = gpu.createTextureView(color); depthView = gpu.createTextureView(depth);
            }
            var encoder = gpu.createCommandEncoder();
            try (var pass = encoder.createRenderPass(() -> "Raster opaque material capture", colorView,
                    Optional.of(new Vector4f()), depthView, OptionalDouble.of(RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE))) {
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("Projection", projection);
                for (var layer : ChunkSectionLayerGroup.OPAQUE.layers()) {
                    ((RasterMaterialLayersInvoker) (Object) sections).totemLumen$renderMaterialLayers(
                            new ChunkSectionLayer[]{layer}, sampler, pass, atlas,
                            Minecraft.getInstance().gameRenderer.lightmap(), pipeline(layer.pipeline(false)), pipeline(layer.pipeline(true)));
                }
            }
            // Explicit diagnostic mode only: black means no opaque/cutout surface, not a lighting result.
            if (rawPreviewRequested()) encoder.copyTextureToTexture(color, original, 0, 0, 0, 0, 0, w, h);
            encoder.submit(); capturedThisFrame = true; frames++;
            if (!logged) {
                logged = true;
                TotemLumenClient.LOGGER.info("RASTER_MATERIAL capture ACTIVE: {}x{}, rawPreview={}, rgbPreview={}, no lightmap/fog; baked vertex shade retained; opaque/cutout only", w, h, rawPreviewRequested(), RGB_PREVIEW);
            }
        } catch (RuntimeException failure) {
            failed = true;
            TotemLumenClient.LOGGER.error("Raster material capture disabled; native rendering unchanged", failure);
            retireTargets();
        } finally { clearPending(); }
    }

    static RenderPipeline pipeline(RenderPipeline source) {
        return PIPELINES.computeIfAbsent(source, original -> {
            var builder = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath("totem-lumen", "material/" + original.getLocation().getPath()))
                    .withVertexShader(Identifier.fromNamespaceAndPath("totem-lumen", "core/raster_material"))
                    .withFragmentShader(Identifier.fromNamespaceAndPath("totem-lumen", "core/raster_material"))
                    .withDepthStencilState(Optional.ofNullable(original.getDepthStencilState()))
                    .withCull(original.isCull()).withPolygonMode(original.getPolygonMode())
                    .withPrimitiveTopology(original.getPrimitiveTopology()).withPushConstantSize(original.pushConstantSize());
            original.getBindGroupLayouts().forEach(builder::withBindGroupLayout);
            original.getShaderDefines().flags().forEach(builder::withShaderDefine);
            original.getShaderDefines().values().forEach((name, value) -> builder.withShaderDefine(name, Float.parseFloat(value)));
            for (int i = 0; i < original.getVertexFormatBindings().size(); i++) {
                var format = original.getVertexFormatBindings().get(i);
                if (format != null) builder.withVertexBinding(i, format);
            }
            for (int i = 0; i < original.getColorTargetStates().size(); i++) builder.withColorTargetState(i, original.getColorTargetStates().get(i));
            return builder.build();
        });
    }

    public static void tickLifecycle(Minecraft client) {
        if (!enabled() || client.level == null) close();
    }

    private static void clearPending() { sections = null; sampler = null; atlas = null; projection = null; }

    public static void close() { clearPending(); retireTargets(); }

    private static void retireTargets() {
        capturedThisFrame = false;
        // RenderPearl defers destruction until recorded GPU work has retired.
        if (colorView != null) colorView.close(); if (depthView != null) depthView.close();
        if (color != null) color.close(); if (depth != null) depth.close();
        colorView = depthView = null; color = depth = null; device = null;
        if (logged) TotemLumenClient.LOGGER.info("RASTER_MATERIAL resources retired: frames={}", frames);
        logged = false; frames = 0;
    }
}
