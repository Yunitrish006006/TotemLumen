package dev.totem.lumen.material;

/** Compact renderer-facing surface response used before resource-pack PBR data is available. */
public record SurfaceProperties(float roughness, float metallic) {
    public static final SurfaceProperties DEFAULT = new SurfaceProperties(0.80f, 0.0f);

    public SurfaceProperties {
        if (!normalized(roughness)) {
            throw new IllegalArgumentException("roughness must be a finite value in [0, 1]");
        }
        if (!normalized(metallic)) {
            throw new IllegalArgumentException("metallic must be a finite value in [0, 1]");
        }
    }

    private static boolean normalized(float value) {
        return Float.isFinite(value) && value >= 0.0f && value <= 1.0f;
    }
}
