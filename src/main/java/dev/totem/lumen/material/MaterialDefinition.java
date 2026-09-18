package dev.totem.lumen.material;

/**
 * Minecraft-independent material definition owned by Totem Lumen.
 *
 * <p>Renderer-facing tuning is intentionally data driven. Light radius/intensity and reflection
 * scale are CPU/GPU data fields rather than shader-specialized constants, so changing block rules
 * does not alter generated GLSL.</p>
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
        float transmissionB,
        float lightRadiusScale,
        float lightIntensityScale,
        float reflectionScale
) {
    public static final MaterialDefinition AIR = new MaterialDefinition(
            "minecraft:air", MaterialFlags.AIR, 0,
            0.0f, 0.0f, 0.0f,
            1.0f, 0.0f, 0.0f, 1.0f,
            1.0f, 1.0f, 1.0f,
            1.0f, 1.0f, 1.0f
    );

    /** Compatibility constructor retaining the previous transmission-aware ABI surface. */
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
            float indexOfRefraction,
            float transmissionR,
            float transmissionG,
            float transmissionB
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
                transmissionR,
                transmissionG,
                transmissionB,
                1.0f,
                1.0f,
                1.0f
        );
    }

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
                1.0f,
                1.0f,
                1.0f,
                1.0f
        );
    }

    /** Compatibility constructor for non-emissive/baseline callers. */
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
                1.0f,
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
        if (!isNormalized(roughness)) {
            throw new IllegalArgumentException("roughness must be in [0, 1]");
        }
        if (!isNormalized(metallic)) {
            throw new IllegalArgumentException("metallic must be in [0, 1]");
        }
        if (!isNormalized(opacity)) {
            throw new IllegalArgumentException("opacity must be in [0, 1]");
        }
        if (!Float.isFinite(indexOfRefraction) || indexOfRefraction <= 0.0f) {
            throw new IllegalArgumentException("indexOfRefraction must be positive and finite");
        }
        if (!isNormalized(transmissionR)
                || !isNormalized(transmissionG)
                || !isNormalized(transmissionB)) {
            throw new IllegalArgumentException("transmission RGB must be in [0, 1]");
        }
        if (!isScale(lightRadiusScale, 8.0f)
                || !isScale(lightIntensityScale, 8.0f)
                || !isScale(reflectionScale, 4.0f)) {
            throw new IllegalArgumentException("runtime material scales are out of range");
        }
    }

    private static boolean isNormalized(float value) {
        return Float.isFinite(value) && value >= 0.0f && value <= 1.0f;
    }

    private static boolean isScale(float value, float max) {
        return Float.isFinite(value) && value >= 0.0f && value <= max;
    }

    public boolean has(int flag) {
        return MaterialFlags.has(flags, flag);
    }
}
