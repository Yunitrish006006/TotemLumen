package dev.totem.lumen.integration;

import dev.totem.lumen.gameplay.light.PackedRgbLight;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientRgbVisualLightSourceTest {
    @Test
    void visualBoostPreservesHueAndCapsIntensity() {
        int source = PackedRgbLight.packRgba(4, 11, 15, 10);
        int boosted = ClientRgbVisualLightSource.brighten(source);
        assertEquals(4, PackedRgbLight.hueRed(boosted));
        assertEquals(11, PackedRgbLight.hueGreen(boosted));
        assertEquals(15, PackedRgbLight.hueBlue(boosted));
        assertEquals(11, PackedRgbLight.alpha(boosted));
        assertEquals(0, ClientRgbVisualLightSource.brighten(0));
        assertEquals(15, PackedRgbLight.alpha(ClientRgbVisualLightSource.brighten(
                PackedRgbLight.packRgba(15, 9, 5, 15)
        )));
    }

    @Test
    void placedVisualLightFallsOffFasterWithoutReducingTheSource() {
        int source = PackedRgbLight.packRgba(4, 11, 15, 15);
        int visual = source;
        for (int step = 0; step < 10; step++) {
            visual = PackedRgbLight.attenuate(visual,
                    ClientRgbVisualLightSource.attenuationLoss(PackedRgbLight.alpha(visual), 1));
        }
        assertEquals(15, PackedRgbLight.alpha(source));
        assertEquals(3, PackedRgbLight.alpha(visual));
        assertEquals(2, ClientRgbVisualLightSource.attenuationLoss(13, 1));
        assertEquals(3, ClientRgbVisualLightSource.attenuationLoss(13, 2));
        assertEquals(1, ClientRgbVisualLightSource.attenuationLoss(11, 1));
    }

    @Test
    void roundedDistanceKeepsDiagonalSpreadCloserToACircleThanManhattanSpread() {
        float diagonal = 1.4142135f;
        assertEquals(1, ClientRgbVisualLightSource.roundedAttenuationLoss(15, 0.0f, diagonal));
        assertEquals(1, ClientRgbVisualLightSource.roundedAttenuationLoss(14, diagonal, diagonal * 2.0f));
        assertEquals(3, ClientRgbVisualLightSource.roundedAttenuationLoss(13, diagonal * 2.0f, diagonal * 3.0f));
    }
}
