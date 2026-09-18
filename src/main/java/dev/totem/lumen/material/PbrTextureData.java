package dev.totem.lumen.material;

/**
 * Immutable texture triplet loaded from one resource-pack sprite.
 *
 * <p>Each LabPBR layer may have its own Minecraft animation timeline. Keeping the source sheet and
 * timeline together lets the GPU packer build one compact, deduplicated animation sequence without
 * re-uploading the whole texture scene every frame.</p>
 */
public record PbrTextureData(
        String spriteId,
        PbrImage albedo,
        PbrImage normal,
        PbrImage specular,
        PbrAnimation albedoAnimation,
        PbrAnimation normalAnimation,
        PbrAnimation specularAnimation
) {
    public PbrTextureData {
        if (spriteId == null || spriteId.isBlank()) {
            throw new IllegalArgumentException("spriteId cannot be blank");
        }
        if (albedo == null) {
            throw new IllegalArgumentException("albedo image is required");
        }
    }

    /** Backward-compatible still-texture constructor used by tests and non-animated loaders. */
    public PbrTextureData(
            String spriteId,
            PbrImage albedo,
            PbrImage normal,
            PbrImage specular
    ) {
        this(spriteId, albedo, normal, specular, null, null, null);
    }

    public boolean hasNormalMap() {
        return normal != null;
    }

    public boolean hasSpecularMap() {
        return specular != null;
    }

    public boolean animated() {
        return albedoAnimation != null
                || normalAnimation != null
                || specularAnimation != null;
    }
}
