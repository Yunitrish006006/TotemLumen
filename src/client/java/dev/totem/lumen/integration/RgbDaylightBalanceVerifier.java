package dev.totem.lumen.integration;

import dev.totem.lumen.gameplay.light.PackedRgbLight;
import net.minecraft.util.LightCoordsUtil;

/** Checks the reported noon-versus-torch regression without starting a client. */
public final class RgbDaylightBalanceVerifier {
    private RgbDaylightBalanceVerifier() {
    }

    public static void main(String[] args) {
        verifySmoothedSkyVertices();
        verifyVanillaDaylight();
        float peak = RgbSurfaceLightMath.illumination(0.2f, 1, 0, 0, 1);
        float nearPeak = RgbSurfaceLightMath.illumination(0.2f, 14f / 15, 0, 0, 1);
        if (!(peak < 1 && peak > nearPeak && nearPeak > 0.75f))
            throw new AssertionError("Bright artificial lights lost highlight detail");
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
            float expectedMoonlit = Math.max(0.24f, nightTorch);
            if (Math.abs(moonlitTorch - expectedMoonlit) > 0.0001f) {
                throw new AssertionError("Moonlight reduced the nighttime block-light gain");
            }
            float partialSky = RgbSurfaceLightMath.illumination(0.04f, channel, 0.5f, 0.5f, 2.0f);
            float expected = Math.max(0.54f, RgbSurfaceLightMath.illumination(0.04f, channel, 0.0f, 0.0f, 1.0f));
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

    private static void verifyVanillaDaylight() {
        var white = new org.joml.Vector3f(1);
        var dark = new org.joml.Vector3f(0);
        var map = new DaylightMap(1, 1, white, dark, 0);
        // Vanilla lightmap.fsh: sky level 10/15 becomes (2/3)/(4-2) = 1/3.
        if (Math.abs(map.at(0, 10) - 1f / 3) > 0.0001f || map.at(2, 15) != 1f
                || map.at(0, 9.5f) != (map.at(0, 9) + map.at(0, 10)) * 0.5f)
            throw new AssertionError("Smoothed skylight differs from the vanilla lightmap");
        var rain = new DaylightMap(1, 0.6f, white, dark, 0);
        var bright = new DaylightMap(1, 0.6f, white, dark, 1);
        if (rain.at(1, 15) >= map.at(1, 15) || bright.at(1, 10) <= rain.at(1, 10)
                || rain.refreshKey == bright.refreshKey)
            throw new AssertionError("Skylight ignored weather or the brightness setting");
        for (long tick : new long[]{1000, 6000, 11000})
            if (DaylightMap.dayWeight(tick) != 1)
                throw new AssertionError("Daytime plateau was darkened at tick " + tick);
        if (DaylightMap.dayWeight(18000) != 0 || DaylightMap.dayWeight(12000) <= 0
                || DaylightMap.dayWeight(12000) >= 1)
            throw new AssertionError("Skylight must fade into the existing night palette");
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
