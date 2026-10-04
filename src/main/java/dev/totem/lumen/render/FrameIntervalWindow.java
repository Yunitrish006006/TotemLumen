package dev.totem.lumen.render;

import java.util.Arrays;

/** Bounded, allocation-free sampling of consecutive eligible frame-start timestamps. */
public final class FrameIntervalWindow {
    public enum State { WARMUP, SAMPLING, COMPLETE, OVERFLOW }
    private final long warmupNanos;
    private final long durationNanos;
    private final long[] intervals;
    private boolean started;
    private boolean warmed;
    private long start;
    private long previous;
    private long elapsed;
    private int count;
    private State state = State.WARMUP;

    public FrameIntervalWindow(long warmupNanos, long durationNanos, int capacity) {
        if (warmupNanos < 0 || durationNanos <= 0 || capacity <= 0) {
            throw new IllegalArgumentException("Invalid frame window limits");
        }
        this.warmupNanos = warmupNanos;
        this.durationNanos = durationNanos;
        this.intervals = new long[capacity];
    }

    public State recordFrame(long now) {
        if (state == State.COMPLETE || state == State.OVERFLOW) return state;
        if (!started) {
            started = true;
            start = previous = now;
            warmed = warmupNanos == 0;
            return state = warmed ? State.SAMPLING : State.WARMUP;
        }
        long interval = now - previous;
        if (interval <= 0) throw new IllegalArgumentException("Non-increasing frame clock");
        previous = now;
        if (!warmed) {
            if (now - start >= warmupNanos) {
                warmed = true;
                state = State.SAMPLING;
            }
            return state;
        }
        if (count == intervals.length) return state = State.OVERFLOW;
        intervals[count++] = interval;
        elapsed = Math.addExact(elapsed, interval);
        if (elapsed >= durationNanos) state = State.COMPLETE;
        return state;
    }

    /** 1% low = reciprocal of the mean of the slowest ceil(N / 100) intervals. */
    public Summary summary() {
        if (state != State.COMPLETE) throw new IllegalStateException("No complete frame window");
        long[] ordered = Arrays.copyOf(intervals, count);
        Arrays.sort(ordered);
        int slowCount = (count + 99) / 100;
        double slowTotal = 0;
        for (int i = count - slowCount; i < count; i++) slowTotal += ordered[i];
        return new Summary(count, elapsed / 1e9, count * 1e9 / elapsed,
                slowCount * 1e9 / slowTotal, ordered[(int) Math.ceil(count * 0.95) - 1] / 1e6);
    }

    public void reset() {
        started = warmed = false;
        start = previous = elapsed = 0;
        count = 0;
        state = State.WARMUP;
    }

    public record Summary(int samples, double seconds, double averageFps, double onePercentLowFps,
                          double intervalP95Ms) { }
}
