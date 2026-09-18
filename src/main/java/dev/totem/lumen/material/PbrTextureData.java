package dev.totem.lumen.material;

/**
 * Immutable texture triplet loaded from one resource-pack sprite.
 *
 * <p>Normal/specular maps may be null when the selected resource pack does not provide them.
 * Albedo is expected for renderer-resolved sprites and is retained for the P18C textured shading
 * path.</p>
 */
public record PbrTextureData(
        String spriteId,
        PbrImage albedo,
        PbrImage normal,
        PbrImage specular
) {
    public PbrTextureData {
        if (spriteId == null || spriteId.isBlank()) {
            throw new IllegalArgumentException("spriteId cannot be blank");
        }
        if (albedo == null) {
            throw new IllegalArgumentException("albedo image is required");
        }
    }

    public boolean hasNormalMap() {
        return normal != null;
    }

    public boolean hasSpecularMap() {
        return specular != null;
    }
}
