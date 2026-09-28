package dev.totem.lumen.integration;

import dev.totem.lumen.gameplay.light.PackedRgbLight;
import net.minecraft.util.LightCoordsUtil;

/** Checks the reported noon-versus-torch regression without starting a client. */
public final class RgbDaylightBalanceVerifier {
    private RgbDaylightBalanceVerifier() {
    }

    public static void main(String[] args) {
        verifySmoothedSkyVertices();
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

    private static void verifySmoothedSkyVertices() {
        int shaded = LightCoordsUtil.smoothPack(0, 0);
        int halfSky = LightCoordsUtil.smoothPack(0, 120);
        int fullSky = LightCoordsUtil.pack(0, 15);
        float shadedLevel = RgbSkyLightMath.vertexSkyLevel(shaded, 15, false);
        float halfLevel = RgbSkyLightMath.vertexSkyLevel(halfSky, 15, false);
        float fullLevel = RgbSkyLightMath.vertexSkyLevel(fullSky, 0, false);
        if (shadedLevel != 0.0f || halfLevel != 7.5f || fullLevel != 15.0f) {
            throw new AssertionError("RGB sky must retain Minecraft's per-vertex smooth light values");
        }
        if (RgbSkyLightMath.vertexSkyLevel(LightCoordsUtil.FULL_BRIGHT, 0, true) != 0.0f) {
            throw new AssertionError("An emissive quad must not create sky light in a cave");
        }
        float shadedBrightness = RgbSurfaceLightMath.illumination(0.04f, 0.0f, shadedLevel / 15.0f, 1.0f, 2.0f);
        float halfBrightness = RgbSurfaceLightMath.illumination(0.04f, 0.0f, halfLevel / 15.0f, 1.0f, 2.0f);
        float fullBrightness = RgbSurfaceLightMath.illumination(0.04f, 0.0f, fullLevel / 15.0f, 1.0f, 2.0f);
        if (!(shadedBrightness < halfBrightness && halfBrightness < fullBrightness)) {
            throw new AssertionError("Sky-lit terrain vertices lost their smooth brightness gradient");
        }
    }
}
