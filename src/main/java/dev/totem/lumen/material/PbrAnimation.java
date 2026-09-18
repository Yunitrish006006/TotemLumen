package dev.totem.lumen.material;

import java.util.Arrays;

/**
 * Immutable per-layer Minecraft texture animation timeline.
 *
 * <p>The timeline is already expanded to one source-frame index per game tick. This keeps shader
 * lookup O(1) and preserves custom frame order plus per-frame durations from PNG mcmeta.</p>
 */
public final class PbrAnimation {
    private final int frameWidth;
    private final int frameHeight;
    private final int[] timelineFrames;
    private final boolean interpolate;

    public PbrAnimation(
            int frameWidth,
            int frameHeight,
            int[] timelineFrames,
            boolean interpolate
    ) {
        if (frameWidth <= 0 || frameHeight <= 0) {
            throw new IllegalArgumentException("animation frame dimensions must be positive");
        }
        if (timelineFrames == null || timelineFrames.length == 0) {
            throw new IllegalArgumentException("animation timeline cannot be empty");
        }
        for (int frame : timelineFrames) {
            if (frame < 0) {
                throw new IllegalArgumentException("animation frame index must be >= 0");
            }
        }
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.timelineFrames = timelineFrames.clone();
        this.interpolate = interpolate;
    }

    public int frameWidth() {
        return frameWidth;
    }

    public int frameHeight() {
        return frameHeight;
    }

    public int timelineLength() {
        return timelineFrames.length;
    }

    public int frameAtTick(int tick) {
        return frameAtTick((long) tick);
    }

    public int frameAtTick(long tick) {
        int index = (int) Math.floorMod(tick, (long) timelineFrames.length);
        return timelineFrames[index];
    }

    public int[] copyTimelineFrames() {
        return timelineFrames.clone();
    }

    public boolean interpolate() {
        return interpolate;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PbrAnimation animation)) return false;
        return frameWidth == animation.frameWidth
                && frameHeight == animation.frameHeight
                && interpolate == animation.interpolate
                && Arrays.equals(timelineFrames, animation.timelineFrames);
    }

    @Override
    public int hashCode() {
        int result = 31 * frameWidth + frameHeight;
        result = 31 * result + Arrays.hashCode(timelineFrames);
        result = 31 * result + Boolean.hashCode(interpolate);
        return result;
    }
}
