package dev.totem.lumen.material;

import java.util.Objects;

/**
 * Data-driven material payload for one dynamic entity type.
 *
 * <p>The GPU shader consumes only fixed material descriptor fields. Entity type names and resource
 * paths are resolved on the CPU so adding another emissive entity does not specialize GLSL.</p>
 */
public record EntityMaterialData(
        String entityTypeId,
        PbrImage emissiveTexture,
        float emissiveGain,
        float alphaCutoff
) {
    public EntityMaterialData {
        Objects.requireNonNull(entityTypeId, "entityTypeId");
        if (entityTypeId.isBlank()) {
            throw new IllegalArgumentException("entityTypeId cannot be blank");
        }
        if (!Float.isFinite(emissiveGain) || emissiveGain < 0.0f || emissiveGain > 8.0f) {
            throw new IllegalArgumentException("emissiveGain must be finite and in [0, 8]");
        }
        if (!Float.isFinite(alphaCutoff) || alphaCutoff < 0.0f || alphaCutoff > 1.0f) {
            throw new IllegalArgumentException("alphaCutoff must be finite and in [0, 1]");
        }
    }

    public boolean hasEmissiveTexture() {
        return emissiveTexture != null && emissiveGain > 0.0f;
    }
}
