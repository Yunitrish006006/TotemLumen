package dev.totem.lumen.integration;

/** Combines baked sky and block light for Minecraft RGB terrain vertices. */
final class RgbSurfaceLightMath {
    private RgbSurfaceLightMath() {
    }

    static float illumination(float ambient, float block, float sky, float daylightCoverage, float blockGain) {
        // The data-pack boost fills the light missing from the sky. A sunny or rainy daytime
        // surface keeps its sky contribution; moonlight does not dim torches at night.
        float effectiveGain = blockGain * (1.0f - daylightCoverage);
        float artificial = ambient + block * effectiveGain;
        // Preserve low and mid tones. A smooth shoulder leaves headroom near a lamp,
        // including where several different colors each reach their channel maximum.
        float excess = Math.max(0.0f, artificial - 0.75f);
        if (excess > 0.0f) artificial = 0.75f + 0.25f * excess / (0.25f + excess);
        return Math.max(ambient + sky, artificial);
    }
}
