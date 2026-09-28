package dev.totem.lumen.integration;

import net.minecraft.util.LightCoordsUtil;

/** Reads Minecraft's already smoothed sky component before RGB replaces the scalar lightmap. */
final class RgbSkyLightMath {
    private RgbSkyLightMath() {
    }

    static float vertexSkyLevel(int lightmap, int fallbackSky, boolean emissive) {
        if (emissive) {
            return fallbackSky;
        }
        return Math.min(15.0f, LightCoordsUtil.smoothSky(lightmap) / 16.0f);
    }
}
