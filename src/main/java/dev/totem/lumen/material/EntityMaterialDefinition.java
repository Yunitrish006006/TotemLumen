package dev.totem.lumen.material;

/**
 * Data-driven dynamic-entity material metadata consumed by the stable P17 GPU ABI.
 *
 * <p>The renderer shader never specializes on an entity type. Entity types resolve to one of these
 * descriptors on the CPU, and the descriptor supplies optional emissive texture data plus scalar
 * tuning.</p>
 */
public record EntityMaterialDefinition(
        String entityTypeId,
        PbrImage emissiveTexture,
        float emissiveGain
) {
    public EntityMaterialDefinition {
        if (entityTypeId == null || entityTypeId.isBlank()) {
            throw new IllegalArgumentException("entityTypeId cannot be blank");
        }
        if (!Float.isFinite(emissiveGain) || emissiveGain < 0.0f || emissiveGain > 8.0f) {
            throw new IllegalArgumentException("emissiveGain must be finite and in [0, 8]");
        }
    }

    public boolean hasEmissiveTexture() {
        return emissiveTexture != null && emissiveGain > 0.0f;
    }
}
