package dev.totem.lumen.integration;

import dev.totem.lumen.gameplay.light.PackedRgbLight;

/** Checks the reported noon-versus-torch regression without starting a client. */
public final class RgbDaylightBalanceVerifier {
    private RgbDaylightBalanceVerifier() {
    }

    public static void main(String[] args) {
        int torch = PackedRgbLight.packRgba(15, 10, 5, 14);
        float[] channels = {
                PackedRgbLight.red(torch) / 15.0f,
                PackedRgbLight.green(torch) / 15.0f,
                PackedRgbLight.blue(torch) / 15.0f
        };
        for (float channel : channels) {
            float noonSky = RgbSurfaceLightMath.illumination(0.04f, 0.0f, 1.0f, 1.0f, 2.0f);
            float noonWithTorch = RgbSurfaceLightMath.illumination(0.04f, channel, 1.0f, 1.0f, 2.0f);
            if (noonSky != noonWithTorch) {
                throw new AssertionError("A torch brightened a level-15 noon surface");
            }
            float nightTorch = RgbSurfaceLightMath.illumination(0.04f, channel, 0.0f, 0.0f, 2.0f);
            if (nightTorch <= 0.04f) {
                throw new AssertionError("Night torch light was lost");
            }
            float moonlitTorch = RgbSurfaceLightMath.illumination(0.04f, channel, 0.2f, 0.0f, 2.0f);
            float expectedMoonlit = 0.04f + Math.max(0.2f, channel * 2.0f);
            if (Math.abs(moonlitTorch - expectedMoonlit) > 0.0001f) {
                throw new AssertionError("Moonlight reduced the nighttime block-light gain");
            }
            float partialSky = RgbSurfaceLightMath.illumination(0.04f, channel, 0.5f, 0.5f, 2.0f);
            float expected = 0.04f + Math.max(0.5f, channel);
            if (Math.abs(partialSky - expected) > 0.0001f) {
                throw new AssertionError("Partial daylight did not taper the block-light boost");
            }
            float rainyNoonSky = RgbSurfaceLightMath.illumination(0.04f, 0.0f, 0.8f, 0.8f, 2.0f);
            float rainyNoonWithTorch = RgbSurfaceLightMath.illumination(0.04f, channel, 0.8f, 0.8f, 2.0f);
            if (rainyNoonSky != rainyNoonWithTorch) {
                throw new AssertionError("A torch brightened a sky-lit rainy noon surface");
            }
        }
    }
}
