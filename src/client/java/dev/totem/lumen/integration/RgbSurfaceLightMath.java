package dev.totem.lumen.integration;

/** Combines baked sky and block light for Minecraft RGB terrain vertices. */
final class RgbSurfaceLightMath {
    private RgbSurfaceLightMath() {
    }

    static float illumination(float ambient, float block, float sky, float daylightCoverage, float blockGain) {
        // The data-pack boost fills the light missing from the sky. A sunny or rainy daytime
        // surface keeps its sky contribution; moonlight does not dim torches at night.
        float effectiveGain = blockGain * (1.0f - daylightCoverage);
        return ambient + Math.max(sky, block * effectiveGain);
    }
}
