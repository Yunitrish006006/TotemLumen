package dev.totem.lumen.world;

/**
 * Server-authoritative lighting semantics for one Minecraft block id.
 *
 * <p>Alpha 31 owns emissive color only. Vanilla/Minecraft block state still owns whether a state
 * emits light and its 0-15 emission level, so a world rule cannot turn a non-emissive block into a
 * light source by itself.</p>
 */
public record LightingWorldRule(float emissionR, float emissionG, float emissionB) {
    public LightingWorldRule {
        if (!isNormalized(emissionR) || !isNormalized(emissionG) || !isNormalized(emissionB)) {
            throw new IllegalArgumentException("emission RGB must contain finite values in [0, 1]");
        }
    }

    private static boolean isNormalized(float value) {
        return Float.isFinite(value) && value >= 0.0f && value <= 1.0f;
    }
}
