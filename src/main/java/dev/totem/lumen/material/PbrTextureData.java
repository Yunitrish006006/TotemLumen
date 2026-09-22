package dev.totem.lumen.material;

/**
 * Immutable texture triplet loaded from one resource-pack sprite.
 *
 * <p>Each LabPBR layer may have its own Minecraft animation timeline. Runtime properties are kept
 * separately from the generated shader so material-rule updates only change GPU data.</p>
 */
public record PbrTextureData(
        String spriteId,
        PbrImage albedo,
        PbrImage normal,
        PbrImage specular,
        PbrAnimation albedoAnimation,
        PbrAnimation normalAnimation,
        PbrAnimation specularAnimation,
        PbrTextureRuntimeProperties runtimeProperties
) {
    public PbrTextureData {
        if (spriteId == null || spriteId.isBlank()) {
            throw new IllegalArgumentException("spriteId cannot be blank");
        }
        if (albedo == null) {
            throw new IllegalArgumentException("albedo image is required");
        }
        if (runtimeProperties == null) {
            runtimeProperties = PbrTextureRuntimeProperties.DEFAULT;
        }
    }

    public PbrTextureData(
            String spriteId,
            PbrImage albedo,
            PbrImage normal,
            PbrImage specular,
            PbrAnimation albedoAnimation,
            PbrAnimation normalAnimation,
            PbrAnimation specularAnimation
    ) {
        this(
                spriteId,
                albedo,
                normal,
                specular,
                albedoAnimation,
                normalAnimation,
                specularAnimation,
                PbrTextureRuntimeProperties.DEFAULT
        );
    }

    /** Backward-compatible still-texture constructor used by tests and non-animated loaders. */
    public PbrTextureData(
            String spriteId,
            PbrImage albedo,
            PbrImage normal,
            PbrImage specular
    ) {
        this(
                spriteId,
                albedo,
                normal,
                specular,
                null,
                null,
                null,
                PbrTextureRuntimeProperties.DEFAULT
        );
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
