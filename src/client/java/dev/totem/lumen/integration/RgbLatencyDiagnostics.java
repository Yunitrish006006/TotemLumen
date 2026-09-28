package dev.totem.lumen.integration;

import dev.totem.lumen.TotemLumenClient;

/** Five-second, development-log counters for the active Minecraft RGB client path. */
public final class RgbLatencyDiagnostics {
    private static final long WINDOW_NANOS = 5_000_000_000L;
    private static long windowStart;
    private static long predictorNanos;
    private static long predictorMaxNanos;
    private static long predictorWork;
    private static long dirtyEnqueueNanos;
    private static long dirtyEnqueueMaxNanos;
    private static int ticks;
    private static int drainedSections;
    private static int maxScans;
    private static int maxRebuilds;
    private static int maxImmediate;
    private static int maxDirty;
    private static int maxSkyDirty;

    private RgbLatencyDiagnostics() {
    }

    public static void recordPredictor(long elapsedNanos, int work, int scans, int rebuilds, int immediate) {
        if (!TotemLumenClient.LOGGER.isDebugEnabled()) {
            return;
        }
        predictorNanos += elapsedNanos;
        predictorMaxNanos = Math.max(predictorMaxNanos, elapsedNanos);
        predictorWork += work;
        maxScans = Math.max(maxScans, scans);
        maxRebuilds = Math.max(maxRebuilds, rebuilds);
        maxImmediate = Math.max(maxImmediate, immediate);
        ticks++;
    }

    public static void recordDirtyDrain(int drained, int dirty, int skyDirty, long enqueueNanos) {
        if (!TotemLumenClient.LOGGER.isDebugEnabled()) {
            return;
        }
        drainedSections += drained;
        maxDirty = Math.max(maxDirty, dirty);
        maxSkyDirty = Math.max(maxSkyDirty, skyDirty);
        dirtyEnqueueNanos += enqueueNanos;
        dirtyEnqueueMaxNanos = Math.max(dirtyEnqueueMaxNanos, enqueueNanos);
        long now = System.nanoTime();
        if (windowStart == 0L) {
            windowStart = now;
        }
        if (now - windowStart < WINDOW_NANOS) {
            return;
        }
        TotemLumenClient.LOGGER.debug(
                "RGB client latency: wallMs={}, ticks={}, predictorAvgUs={}, predictorMaxUs={}, "
                        + "workAvg={}, scansMax={}, rebuildsMax={}, immediateMax={}, "
                        + "dirtyMax={}, skyDirtyMax={}, meshDrained={}, dirtyEnqueueAvgUs={}, "
                        + "dirtyEnqueueMaxUs={}",
                (now - windowStart) / 1_000_000L,
                ticks,
                ticks == 0 ? 0 : predictorNanos / ticks / 1_000L,
                predictorMaxNanos / 1_000L,
                ticks == 0 ? 0 : predictorWork / ticks,
                maxScans,
                maxRebuilds,
                maxImmediate,
                maxDirty,
                maxSkyDirty,
                drainedSections,
                ticks == 0 ? 0 : dirtyEnqueueNanos / ticks / 1_000L,
                dirtyEnqueueMaxNanos / 1_000L
        );
        windowStart = now;
        predictorNanos = 0L;
        predictorMaxNanos = 0L;
        predictorWork = 0L;
        dirtyEnqueueNanos = 0L;
        dirtyEnqueueMaxNanos = 0L;
        ticks = 0;
        drainedSections = 0;
        maxScans = 0;
        maxRebuilds = 0;
        maxImmediate = 0;
        maxDirty = 0;
        maxSkyDirty = 0;
    }
}
