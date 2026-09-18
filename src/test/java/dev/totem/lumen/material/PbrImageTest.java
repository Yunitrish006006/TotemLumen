package dev.totem.lumen.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class PbrImageTest {
    @Test
    void ownsDefensivePixelCopy() {
        int[] source = {0xFF010203, 0xFF040506};
        PbrImage image = new PbrImage(2, 1, source);
        source[0] = 0;

        assertEquals(0xFF010203, image.pixelArgb(0, 0));
        int[] copy = image.copyArgb();
        copy[1] = 0;
        assertEquals(0xFF040506, image.pixelArgb(1, 0));
    }

    @Test
    void nearestSamplingWrapsUv() {
        PbrImage image = new PbrImage(
                2,
                2,
                new int[]{
                        0xFF000001, 0xFF000002,
                        0xFF000003, 0xFF000004
                }
        );

        assertEquals(0xFF000001, image.sampleNearest(0.1f, 0.1f));
        assertEquals(0xFF000004, image.sampleNearest(0.75f, 0.75f));
        assertEquals(0xFF000002, image.sampleNearest(-0.25f, 0.1f));
    }

    @Test
    void rejectsInvalidPayloads() {
        assertThrows(IllegalArgumentException.class, () -> new PbrImage(0, 1, new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new PbrImage(2, 2, new int[3]));
    }
}
