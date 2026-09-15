package dev.totem.lumen.world;

/**
 * Server-authoritative lighting semantics for one Minecraft block id.
 *
 * <p>Emission color is synchronized to Totem Lumen clients. Gameplay strength is server-only and
 * controls the stable 0..15 source intensity used by the authoritative gameplay light field. A
 * value of {@link #USE_BLOCK_STATE} preserves the block state's vanilla emission level.</p>
 */
public record LightingWorldRule(float emissionR, float emissionG, float emissionB, int gameplayStrength) {
    public static final int USE_BLOCK_STATE = -1;

    public LightingWorldRule(float emissionR, float emissionG, float emissionB) {
        this(emissionR, emissionG, emissionB, USE_BLOCK_STATE);
    }

    public LightingWorldRule {
        if (!isNormalized(emissionR) || !isNormalized(emissionG) || !isNormalized(emissionB)) {
            throw new IllegalArgumentException("emission RGB must contain finite values in [0, 1]");
        }
        if (gameplayStrength < USE_BLOCK_STATE || gameplayStrength > 15) {
            throw new IllegalArgumentException("gameplay strength must be -1 or an integer in [0, 15]");
        }
    }

    public int gameplayStrengthOr(int blockStateEmission) {
        return gameplayStrength == USE_BLOCK_STATE ? blockStateEmission : gameplayStrength;
    }

    private static boolean isNormalized(float value) {
        return Float.isFinite(value) && value >= 0.0f && value <= 1.0f;
    }
}
