package dev.totem.lumen.material;

/**
 * Minecraft-independent baseline material definition.
 *
 * <p>Texture/PBR handles are intentionally absent in P2. They can be appended later without making
 * the scene depend on BlockState or Minecraft renderer classes.</p>
 */
public record MaterialDefinition(
        String sourceId,
        int flags,
        int emissionLevel,
        float roughness,
        float metallic,
        float opacity,
        float indexOfRefraction
) {
    public static final MaterialDefinition AIR = new MaterialDefinition(
            "minecraft:air", MaterialFlags.AIR, 0, 1.0f, 0.0f, 0.0f, 1.0f
    );

    public MaterialDefinition {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        if (emissionLevel < 0 || emissionLevel > 15) {
            throw new IllegalArgumentException("emissionLevel must be in [0, 15]");
        }
        if (roughness < 0.0f || roughness > 1.0f) {
            throw new IllegalArgumentException("roughness must be in [0, 1]");
        }
        if (metallic < 0.0f || metallic > 1.0f) {
            throw new IllegalArgumentException("metallic must be in [0, 1]");
        }
        if (opacity < 0.0f || opacity > 1.0f) {
            throw new IllegalArgumentException("opacity must be in [0, 1]");
        }
        if (indexOfRefraction <= 0.0f) {
            throw new IllegalArgumentException("indexOfRefraction must be positive");
        }
    }

    public boolean has(int flag) {
        return MaterialFlags.has(flags, flag);
    }
}
