package dev.totem.lumen.integration;

import net.minecraft.util.Mth;
import org.joml.Vector3fc;

/** The sky-only column of Minecraft 26.3's lightmap.fsh, sampled at smooth vertex coordinates. */
final class DaylightMap {
    private final float[] colors = new float[16 * 3];
    final float coverage;
    final float weight;
    final int refreshKey;

    DaylightMap(float weight, float factor, Vector3fc sky, Vector3fc ambient, float gamma) {
        this.weight = weight;
        coverage = Mth.clamp(factor, 0f, 1f);
        for (int level = 0; level <= 15; level++) {
            float normalized = level / 15f;
            float strength = factor * normalized / (4f - 3f * normalized);
            int i = level * 3;
            colors[i] = Mth.clamp(ambient.x() + strength * sky.x(), 0f, 1f);
            colors[i + 1] = Mth.clamp(ambient.y() + strength * sky.y(), 0f, 1f);
            colors[i + 2] = Mth.clamp(ambient.z() + strength * sky.z(), 0f, 1f);
            float peak = Math.max(colors[i], Math.max(colors[i + 1], colors[i + 2]));
            // (1 - (1 - peak)^4) / peak, including its finite limit at zero.
            float inverse = 1f - peak;
            float gain = Mth.lerp(gamma, 1f, 1f + inverse * (1f + inverse * (1f + inverse)));
            for (int c = 0; c < 3; c++) colors[i + c] *= gain;
        }
        // Quantize the baked palette for section refreshes, including weather and gamma changes.
        int key = 31 * Math.round(weight * 12) + Math.round(coverage * 16);
        for (int level : new int[]{8, 15}) {
            for (int c = 0; c < 3; c++) key = 31 * key + Math.round(colors[level * 3 + c] * 16);
        }
        refreshKey = key;
    }

    float at(int channel, float level) {
        float coordinate = Mth.clamp(level, 0f, 15f);
        int lower = (int) coordinate;
        return Mth.lerp(coordinate - lower, colors[lower * 3 + channel],
                colors[Math.min(15, lower + 1) * 3 + channel]);
    }

    // The vanilla overworld daylight track is on its daytime plateau from tick 730 to 11270.
    static float dayWeight(long clock) {
        int tick = Math.floorMod(clock, 24000);
        int fromNoon = Math.min(Math.abs(tick - 6000), 24000 - Math.abs(tick - 6000));
        return Mth.clamp((7140 - fromNoon) / 1870f, 0f, 1f);
    }
}
