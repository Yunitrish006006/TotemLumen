package dev.totem.lumen.gameplay.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RgbLightAttenuationTest {
    @Test
    void clientAndServerUseTheSameRadialDistanceScale() {
        assertEquals(18, RgbLightAttenuation.ROUND_STEPS.size());
        assertEquals(1.20f, RgbLightAttenuation.radialDistance(0, 0, 0, 1, 0, 0), 0.0001f);
        assertEquals(1.20f * (float) Math.sqrt(2.0),
                RgbLightAttenuation.radialDistance(0, 0, 0, 1, 1, 0), 0.0001f);
    }

    @Test
    void radialLossMatchesTheSharedFiveLevelCadence() {
        assertEquals(1, RgbLightAttenuation.roundedAttenuationLoss(14, 0.0f, 1.20f));
        assertEquals(2, RgbLightAttenuation.roundedAttenuationLoss(13, 0.0f, 1.20f));
        assertEquals(2, RgbLightAttenuation.roundedAttenuationLoss(13, 1.20f, 2.40f));
    }

    @Test
    void testPackDoublesLossWithoutChangingSourceHue() {
        int source = PackedRgbLight.packRgba(15, 8, 2, 15);
        int normal = RgbLightAttenuation.attenuateRounded(source, 0.0f, 1.2f);
        int doubled = RgbLightAttenuation.attenuateRounded(source, 0.0f, 1.2f, 2.0f);
        assertEquals(14, PackedRgbLight.alpha(normal));
        assertEquals(13, PackedRgbLight.alpha(doubled));
        assertEquals(PackedRgbLight.hueRed(source), PackedRgbLight.hueRed(doubled));
    }

}
