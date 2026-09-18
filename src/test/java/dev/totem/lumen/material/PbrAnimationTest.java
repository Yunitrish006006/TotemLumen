package dev.totem.lumen.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class PbrAnimationTest {
    @Test
    void frameTimelineWrapsByGameTick() {
        PbrAnimation animation = new PbrAnimation(
                16,
                16,
                new int[]{0, 0, 1, 1, 2},
                false
        );

        assertEquals(0, animation.frameAtTick(0));
        assertEquals(1, animation.frameAtTick(2));
        assertEquals(2, animation.frameAtTick(4));
        assertEquals(0, animation.frameAtTick(5));
        assertEquals(2, animation.frameAtTick(-1));
    }

    @Test
    void ownsTimelineCopy() {
        int[] timeline = {0, 1};
        PbrAnimation animation = new PbrAnimation(1, 1, timeline, true);
        timeline[0] = 9;

        assertEquals(0, animation.frameAtTick(0));
        int[] copy = animation.copyTimelineFrames();
        copy[1] = 9;
        assertEquals(1, animation.frameAtTick(1));
        assertTrue(animation.interpolate());
    }

    @Test
    void rejectsInvalidTimeline() {
        assertThrows(IllegalArgumentException.class, () ->
                new PbrAnimation(0, 1, new int[]{0}, false)
        );
        assertThrows(IllegalArgumentException.class, () ->
                new PbrAnimation(1, 1, new int[0], false)
        );
        assertThrows(IllegalArgumentException.class, () ->
                new PbrAnimation(1, 1, new int[]{-1}, false)
        );
    }
}
