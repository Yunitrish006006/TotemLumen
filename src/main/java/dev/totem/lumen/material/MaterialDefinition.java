package dev.totem.lumen.material;

/**
 * Minecraft-independent material definition owned by Totem Lumen.
 *
 * <p>P15 adds a compact RGB transmission tint. Opacity remains the scalar absorption/coverage
 * control and IOR remains reserved for the later refraction path. Keeping transmission metadata in
 * the material definition avoids hard-coding Minecraft block identifiers in Vulkan shaders.</p>
 */
public record MaterialDefinition(
        String sourceId,
        int flags,
        int emissionLevel,
        float emissionR,
        float emissionG,
        float emissionB,
        float roughness,
        float metallic,
        float opacity,
        float indexOfRefraction,
        float transmissionR,
        float transmissionG,
        float transmissionB
) {
    public static final MaterialDefinition AIR = new MaterialDefinition(
            "minecraft:air", MaterialFlags.AIR, 0,
            0.0f, 0.0f, 0.0f,
            1.0f, 0.0f, 0.0f, 1.0f,
            1.0f, 1.0f, 1.0f
    );

    /** Compatibility constructor for callers that already provide explicit emissive RGB. */
    public MaterialDefinition(
            String sourceId,
            int flags,
            int emissionLevel,
            float emissionR,
            float emissionG,
            float emissionB,
            float roughness,
            float metallic,
            float opacity,
            float indexOfRefraction
    ) {
        this(
                sourceId,
                flags,
                emissionLevel,
                emissionR,
                emissionG,
                emissionB,
                roughness,
                metallic,
                opacity,
                indexOfRefraction,
                1.0f,
                1.0f,
                1.0f
        );
    }

    /**
     * Compatibility constructor for non-emissive/baseline callers. Emissive materials created
     * through this constructor default to neutral white and transmission defaults to neutral RGB.
     */
    public MaterialDefinition(
            String sourceId,
            int flags,
            int emissionLevel,
            float roughness,
            float metallic,
            float opacity,
            float indexOfRefraction
    ) {
        this(
                sourceId,
                flags,
                emissionLevel,
                emissionLevel > 0 ? 1.0f : 0.0f,
                emissionLevel > 0 ? 1.0f : 0.0f,
                emissionLevel > 0 ? 1.0f : 0.0f,
                roughness,
                metallic,
                opacity,
                indexOfRefraction,
                1.0f,
                1.0f,
                1.0f
        );
    }

    public MaterialDefinition {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        if (emissionLevel < 0 || emissionLevel > 15) {
            throw new IllegalArgumentException("emissionLevel must be in [0, 15]");
        }
        if (!isNormalized(emissionR) || !isNormalized(emissionG) || !isNormalized(emissionB)) {
            throw new IllegalArgumentException("emission RGB must be in [0, 1]");
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
        if (!isNormalized(transmissionR)
                || !isNormalized(transmissionG)
                || !isNormalized(transmissionB)) {
            throw new IllegalArgumentException("transmission RGB must be in [0, 1]");
        }
    }

    private static boolean isNormalized(float value) {
        return Float.isFinite(value) && value >= 0.0f && value <= 1.0f;
    }

    public boolean has(int flag) {
        return MaterialFlags.has(flags, flag);
    }
}
