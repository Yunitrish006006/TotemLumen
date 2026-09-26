package dev.totem.lumen.world;

/** Data-pack-controlled block-light presentation and shared propagation tuning. */
public record LightingTuning(float brightnessMultiplier, float attenuationMultiplier) {
    public static final LightingTuning DEFAULT = new LightingTuning(1.0f, 1.0f);

    public LightingTuning {
        if (!Float.isFinite(brightnessMultiplier) || brightnessMultiplier < 0.0f
                || brightnessMultiplier > 4.0f) {
            throw new IllegalArgumentException("brightness_multiplier must be finite and in [0, 4]");
        }
        if (!Float.isFinite(attenuationMultiplier) || attenuationMultiplier < 1.0f
                || attenuationMultiplier > 4.0f) {
            throw new IllegalArgumentException("attenuation_multiplier must be finite and in [1, 4]");
        }
    }
}
