package dev.totem.lumen.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameIntervalWindowTest {
    @Test void excludesWarmupAndUsesElapsedTimeNotMeanInstantaneousFps() {
        var window = new FrameIntervalWindow(100, 100_000_000L, 10);
        assertEquals(FrameIntervalWindow.State.WARMUP, window.recordFrame(0));
        assertEquals(FrameIntervalWindow.State.SAMPLING, window.recordFrame(100));
        window.recordFrame(10_000_100);
        assertEquals(FrameIntervalWindow.State.COMPLETE, window.recordFrame(100_000_100));
        var result = window.summary();
        assertEquals(2, result.samples());
        assertEquals(20, result.averageFps(), 1e-9);
        assertEquals(1e9 / 90_000_000, result.onePercentLowFps(), 1e-9);
        assertEquals(90, result.intervalP95Ms(), 1e-9);
    }

    @Test void onePercentUsesCeilingAndMeanOfSlowTail() {
        var window = new FrameIntervalWindow(0, 1030, 101);
        long now = 1;
        window.recordFrame(now);
        for (int i = 0; i < 99; i++) window.recordFrame(now += 10);
        window.recordFrame(now += 15);
        window.recordFrame(now += 25);
        assertEquals(101, window.summary().samples());
        assertEquals(1e9 / 20, window.summary().onePercentLowFps(), 1e-9);
    }

    @Test void capacityOverflowCannotProducePartialFps() {
        var window = new FrameIntervalWindow(0, 100, 2);
        window.recordFrame(1);
        window.recordFrame(2);
        window.recordFrame(3);
        assertEquals(FrameIntervalWindow.State.OVERFLOW, window.recordFrame(4));
        assertThrows(IllegalStateException.class, window::summary);
        window.reset();
        window.recordFrame(5);
        assertEquals(FrameIntervalWindow.State.COMPLETE, window.recordFrame(105));
        assertEquals(1, window.summary().samples());
    }

    @Test void resetDropsPartialWindowAndCompletedWindowIsImmutable() {
        var window = new FrameIntervalWindow(0, 100, 10);
        window.recordFrame(1);
        window.recordFrame(51);
        window.reset();
        window.recordFrame(500);
        window.recordFrame(600);
        var expected = window.summary();
        window.recordFrame(900);
        assertEquals(expected, window.summary());
        assertEquals(1, expected.samples());
    }

    @Test void validatesLimitsClockAndIncompleteWindows() {
        assertThrows(IllegalArgumentException.class, () -> new FrameIntervalWindow(-1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new FrameIntervalWindow(0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new FrameIntervalWindow(0, 1, 0));
        var window = new FrameIntervalWindow(0, 100, 2);
        window.recordFrame(-200);
        assertThrows(IllegalArgumentException.class, () -> window.recordFrame(-200));
        assertThrows(IllegalStateException.class, window::summary);
        window.recordFrame(-100);
        assertEquals(1, window.summary().samples());
    }
}
