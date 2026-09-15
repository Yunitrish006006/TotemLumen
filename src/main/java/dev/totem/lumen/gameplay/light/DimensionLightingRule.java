package dev.totem.lumen.gameplay.light;

/** Server gameplay ambient-light rule for one dimension. */
public record DimensionLightingRule(
        EmissionColor environmentColor,
        int gameplayStrength,
        boolean affectsSpawning
) {
    public DimensionLightingRule {
        if (environmentColor == null) {
            throw new IllegalArgumentException("environmentColor must not be null");
        }
        if (gameplayStrength < 0 || gameplayStrength > PackedRgbLight.MAX_CHANNEL) {
            throw new IllegalArgumentException("gameplayStrength must be in [0, 15]");
        }
    }

    public int packedEnvironment() {
        return PackedRgbLight.fromNormalized(environmentColor, gameplayStrength);
    }
}
