package dev.totem.lumen.integration;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.render.FrameIntervalWindow;
import dev.totem.lumen.render.RendererSettings;
import dev.totem.lumen.vulkan.P5StableLookupRenderer;
import net.minecraft.client.Minecraft;

/** Opt-in frame-start interval sampling, shared by every profile. Client-thread only. */
public final class RgbFrameMetrics {
    // Keep the original property as an alias for existing developer launch configurations.
    private static final boolean ENABLED = Boolean.getBoolean("totem.lumen.frameMetrics")
            || Boolean.getBoolean("totem.lumen.rgb.frameMetrics");
    private static final FrameIntervalWindow WINDOW = ENABLED
            ? new FrameIntervalWindow(20_000_000_000L, 60_000_000_000L, 120_000) : null;
    private static Object level;
    private static RendererSettings.RenderProfile profile;
    private static long revision;
    private static int width, height, renderDistance, simulationDistance, fpsLimit;
    private static boolean vsync;

    private RgbFrameMetrics() { }

    public static void frameStart(Minecraft client, boolean renderLevel) {
        if (!ENABLED) return;
        if (!eligible(client, renderLevel)) {
            reset();
            return;
        }
        var current = RendererSettings.renderProfile();
        int w = client.getWindow().getWidth(), h = client.getWindow().getHeight();
        int rd = client.options.renderDistance().get(), sd = client.options.simulationDistance().get();
        int cap = client.options.framerateLimit().get();
        boolean sync = client.options.enableVsync().get();
        long currentRevision = RendererSettings.revision();
        if (client.level != level || current != profile || currentRevision != revision
                || w != width || h != height || rd != renderDistance || sd != simulationDistance
                || cap != fpsLimit || sync != vsync) {
            reset();
            level = client.level;
            profile = current;
            revision = currentRevision;
            width = w;
            height = h;
            renderDistance = rd;
            simulationDistance = sd;
            fpsLimit = cap;
            vsync = sync;
        }
        var state = WINDOW.recordFrame(System.nanoTime());
        if (state == FrameIntervalWindow.State.OVERFLOW) {
            TotemLumenClient.LOGGER.warn("Frame metrics window discarded: 120000-sample capacity exceeded");
            reset();
        } else if (state == FrameIntervalWindow.State.COMPLETE) {
            var result = WINDOW.summary();
            Runtime runtime = Runtime.getRuntime();
            TotemLumenClient.LOGGER.info(
                    "Profile frame metrics: profile={}, samples={}, seconds={}, averageFps={}, onePercentLowFps={}, "
                            + "intervalP95Ms={}, viewport={}x{}, renderDistance={}, simulationDistance={}, "
                            + "fpsLimit={}, vsync={}, gi={}, shadow={}, rayDistance={}, internalResolution={}, "
                            + "reflections={}, temporal={}, denoise={}, rasterMaterialLighting={}, heapUsedMiB={}, heapCommittedMiB={}, heapMaxMiB={}",
                    profile, result.samples(), rounded(result.seconds()), rounded(result.averageFps()),
                    rounded(result.onePercentLowFps()), rounded(result.intervalP95Ms()), width, height,
                    renderDistance, simulationDistance, fpsLimit, vsync, RendererSettings.giQuality(),
                    RendererSettings.rasterLightingEnabled() ? (RasterMaterialCapture.lightingPreviewRequested()
                            ? "DIRECT_RGB_POINT_SHADOWS" : "AMBIENT_OCCLUSION_ONLY") : RendererSettings.shadowQuality(), RendererSettings.rasterLightingEnabled()
                            ? dev.totem.lumen.render.RasterLightingVolume.RAY_DISTANCE : RendererSettings.rayDistance(), RendererSettings.internalResolution(),
                    !RendererSettings.rasterLightingEnabled() && RendererSettings.reflectionsEnabled(),
                    RendererSettings.rasterLightingEnabled() ? "OFF" : RendererSettings.temporalQuality(),
                    RendererSettings.rasterLightingEnabled() ? "OFF" : RendererSettings.denoiseQuality(),
                    RendererSettings.rasterLightingEnabled() && RasterMaterialCapture.lightingPreviewRequested(),
                    (runtime.totalMemory() - runtime.freeMemory()) / 1048576L,
                    runtime.totalMemory() / 1048576L, runtime.maxMemory() / 1048576L);
            // Sorting/logging is outside sampling; the next window gets a fresh warmup.
            reset();
        }
    }

    public static void frameEnd(Minecraft client, boolean renderLevel) {
        if (ENABLED && !eligible(client, renderLevel)) reset();
    }

    private static boolean eligible(Minecraft client, boolean renderLevel) {
        if (client.level == null || !renderLevel || client.isPaused() || client.gui.screen() != null
                || client.gui.overlay() != null || !client.isWindowActive()) return false;
        if (RendererSettings.rasterLightingEnabled()) return RasterLightingRenderer.ready();
        return RendererSettings.renderProfile() != RendererSettings.RenderProfile.TOTEM_LUMEN
                || (P5StableLookupRenderer.readyForWorldTakeover()
                && P5StableLookupRenderer.mode() == P5StableLookupRenderer.DebugMode.GI_COMPOSITE);
    }

    private static double rounded(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static void reset() {
        WINDOW.reset();
        level = null;
        profile = null;
    }
}
