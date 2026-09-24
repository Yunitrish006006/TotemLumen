package dev.totem.lumen.gameplay.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PackedRgbLightTest {
    @Test
    void packsChromaAndIntensityIntoSixteenBits() {
        int packed = PackedRgbLight.pack(15, 6, 2);
        assertEquals(15, PackedRgbLight.red(packed));
        assertEquals(6, PackedRgbLight.green(packed));
        assertEquals(2, PackedRgbLight.blue(packed));
        assertEquals(15, PackedRgbLight.maxChannel(packed));
        assertEquals(15, PackedRgbLight.alpha(packed));
        int dimmed = PackedRgbLight.packRgba(15, 6, 2, 7);
        assertEquals(7, PackedRgbLight.alpha(dimmed));
        assertEquals(7, PackedRgbLight.red(dimmed));
        assertEquals(3, PackedRgbLight.green(dimmed));
        assertEquals(1, PackedRgbLight.blue(dimmed));
    }

    @Test
    void clampsAndAttenuatesWithoutRemovingWeakerChannelsFirst() {
        int packed = PackedRgbLight.pack(20, -4, 5);
        assertEquals(PackedRgbLight.pack(15, 0, 5), packed);
        assertEquals(PackedRgbLight.pack(12, 0, 4), PackedRgbLight.attenuate(packed, 3));
        assertEquals(PackedRgbLight.pack(14, 0, 5), PackedRgbLight.attenuate(packed, 0));
    }

    @Test
    void warmTorchKeepsChromaWhenIntensityFalls() {
        int packed = PackedRgbLight.fromNormalized(new EmissionColor(1.0f, 0.64f, 0.34f), 14);
        assertEquals(14, PackedRgbLight.alpha(packed));
        assertEquals(14, PackedRgbLight.red(packed));
        assertEquals(9, PackedRgbLight.green(packed));
        assertEquals(5, PackedRgbLight.blue(packed));
        int originalRed = PackedRgbLight.hueRed(packed);
        int originalGreen = PackedRgbLight.hueGreen(packed);
        int originalBlue = PackedRgbLight.hueBlue(packed);
        while (PackedRgbLight.maxChannel(packed) > 1) {
            packed = PackedRgbLight.attenuate(packed, 1);
        }
        assertEquals(originalRed, PackedRgbLight.hueRed(packed));
        assertEquals(originalGreen, PackedRgbLight.hueGreen(packed));
        assertEquals(originalBlue, PackedRgbLight.hueBlue(packed));
        assertEquals(1, PackedRgbLight.red(packed));
        assertEquals(1, PackedRgbLight.green(packed));
        assertEquals(0, PackedRgbLight.attenuate(packed, 1));
    }

    @Test
    void combinesSourcesByComponentMaximum() {
        int warm = PackedRgbLight.pack(12, 4, 1);
        int cool = PackedRgbLight.pack(2, 8, 11);
        int combined = PackedRgbLight.componentMax(warm, cool);
        assertEquals(PackedRgbLight.pack(12, 8, 11), combined);
        assertEquals(combined, PackedRgbLight.componentMax(combined, warm));
        assertEquals(combined, PackedRgbLight.componentMax(combined, cool));
    }

    @Test
    void spectralSensitivityCanIgnorePartOfRedLight() {
        GameplayLightSensitivity nether = new GameplayLightSensitivity(
                0.35f, 1.0f, 1.0f,
                0.10f, 1.0f, 1.0f
        );
        int red = PackedRgbLight.pack(12, 1, 0);
        assertEquals(4.2f, PackedRgbLight.effectiveBrightness(red, nether), 0.0001f);
        assertEquals(1.2f, nether.environmentBrightness(red), 0.0001f);
    }

    @Test
    void sectionTracksWhetherItCanBePruned() {
        ServerLightSection section = new ServerLightSection();
        assertTrue(section.isEmpty());
        assertTrue(section.set(1, 2, 3, PackedRgbLight.pack(4, 5, 6)));
        assertFalse(section.isEmpty());
        assertEquals(PackedRgbLight.pack(4, 5, 6), section.get(1, 2, 3));
        assertTrue(section.set(1, 2, 3, 0));
        assertTrue(section.isEmpty());
    }
}
