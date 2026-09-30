package dev.totem.lumen.integration;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.render.RendererSettings;
import net.minecraft.client.Minecraft;

import java.util.Arrays;

/** Opt-in wall-clock frame sampling for matched Minecraft Pure/RGB runtime runs. */
public final class RgbFrameMetrics {
    private static final boolean ENABLED = Boolean.getBoolean("totem.lumen.rgb.frameMetrics");
    private static final int WARMUP_FRAMES = 200;
    private static final int SAMPLE_FRAMES = 600;
    private static final long[] INTERVALS = new long[SAMPLE_FRAMES];
    private static final long[] RENDER_TIMES = new long[SAMPLE_FRAMES];

    private static RendererSettings.RenderProfile profile;
    private static String dimension;
    private static int warmup;
    private static int samples;
    private static long lastStart;
    private static long frameStart;
    private static boolean sampleReady;

    private RgbFrameMetrics() {
    }

    public static void frameStart(Minecraft client, boolean renderLevel) {
        if (!ENABLED) {
            return;
        }
        RendererSettings.RenderProfile current = RendererSettings.renderProfile();
        String currentDimension = client.level == null
                ? null : client.level.dimension().identifier().toString();
        if (currentDimension == null || !renderLevel || client.isPaused()
                || (current != RendererSettings.RenderProfile.MINECRAFT_PURE
                && current != RendererSettings.RenderProfile.MINECRAFT_RGB)) {
            reset();
            return;
        }
        if (current != profile || !currentDimension.equals(dimension)) {
            reset();
            profile = current;
            dimension = currentDimension;
        }
        long now = System.nanoTime();
        frameStart = now;
        sampleReady = false;
        if (lastStart != 0L) {
            if (warmup < WARMUP_FRAMES) {
                warmup++;
            } else if (samples < SAMPLE_FRAMES) {
                INTERVALS[samples] = now - lastStart;
                sampleReady = true;
            }
        }
        lastStart = now;
    }

    public static void frameEnd(Minecraft client, boolean renderLevel) {
        if (!ENABLED || !sampleReady || frameStart == 0L || client.level == null
                || !renderLevel || client.isPaused() || samples >= SAMPLE_FRAMES) {
            return;
        }
        RENDER_TIMES[samples] = System.nanoTime() - frameStart;
        samples++;
        if (samples == SAMPLE_FRAMES) {
            TotemLumenClient.LOGGER.info(
                    "RGB frame metrics: profile={}, dimension={}, renderDistance={}, samples={}, "
                            + "intervalAvgMs={}, intervalP50Ms={}, intervalP95Ms={}, intervalP99Ms={}, "
                            + "renderAvgMs={}, renderP95Ms={}",
                    profile, dimension, client.options.renderDistance().get(), samples,
                    millis(average(INTERVALS)), millis(percentile(INTERVALS, 0.50)),
                    millis(percentile(INTERVALS, 0.95)), millis(percentile(INTERVALS, 0.99)),
                    millis(average(RENDER_TIMES)), millis(percentile(RENDER_TIMES, 0.95))
            );
            samples = 0;
        }
    }

    private static long average(long[] values) {
        long total = 0L;
        for (long value : values) {
            total += value;
        }
        return total / values.length;
    }

    private static long percentile(long[] values, double fraction) {
        long[] ordered = values.clone();
        Arrays.sort(ordered);
        return ordered[(int) Math.ceil(fraction * ordered.length) - 1];
    }

    private static double millis(long nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }

    private static void reset() {
        profile = null;
        dimension = null;
        warmup = 0;
        samples = 0;
        lastStart = 0L;
        frameStart = 0L;
        sampleReady = false;
    }
}
