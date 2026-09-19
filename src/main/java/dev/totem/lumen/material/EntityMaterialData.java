package dev.totem.lumen.material;

import java.util.Objects;

/**
 * Data-driven material payload for one dynamic entity type.
 *
 * <p>The fixed GPU descriptor owns both base and emissive texture handles plus common optical
 * scalars. Entity type names and resource paths are resolved on the CPU, so adding or tuning an
 * entity material does not specialize GLSL.</p>
 */
public record EntityMaterialData(
        String entityTypeId,
        PbrImage albedoTexture,
        PbrImage emissiveTexture,
        float emissiveGain,
        float alphaCutoff,
        float roughness,
        float metallic,
        float reflectionScale
) {
    public EntityMaterialData {
        Objects.requireNonNull(entityTypeId, "entityTypeId");
        if (entityTypeId.isBlank()) {
            throw new IllegalArgumentException("entityTypeId cannot be blank");
        }
        emissiveGain = finiteRange(emissiveGain, 0.0f, 8.0f, "emissiveGain");
        alphaCutoff = finiteRange(alphaCutoff, 0.0f, 1.0f, "alphaCutoff");
        roughness = finiteRange(roughness, 0.0f, 1.0f, "roughness");
        metallic = finiteRange(metallic, 0.0f, 1.0f, "metallic");
        reflectionScale = finiteRange(reflectionScale, 0.0f, 4.0f, "reflectionScale");
    }

    /** Compatibility constructor for emissive-only entity materials. */
    public EntityMaterialData(
            String entityTypeId,
            PbrImage emissiveTexture,
            float emissiveGain,
            float alphaCutoff
    ) {
        this(
                entityTypeId,
                null,
                emissiveTexture,
                emissiveGain,
                alphaCutoff,
                0.8f,
                0.0f,
                1.0f
        );
    }

    public boolean hasAlbedoTexture() {
        return albedoTexture != null;
    }

    public boolean hasEmissiveTexture() {
        return emissiveTexture != null && emissiveGain > 0.0f;
    }

    private static float finiteRange(float value, float min, float max, String name) {
        if (!Float.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(
                    name + " must be finite and in [" + min + ", " + max + "]"
            );
        }
        return value;
    }
}
