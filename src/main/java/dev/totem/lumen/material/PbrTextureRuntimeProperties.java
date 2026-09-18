package dev.totem.lumen.material;

/**
 * Data-driven runtime controls for one resolved texture.
 *
 * <p>These values are uploaded through the stable P18 texture descriptor ABI. Changing a texture
 * rule therefore changes GPU data, not generated GLSL, so existing SPIR-V and driver pipeline
 * caches remain reusable.</p>
 */
public record PbrTextureRuntimeProperties(
        float baselineEmissionScale,
        float labPbrEmissionScale,
        float roughnessScale,
        float normalStrength,
        float alphaCutoff
) {
    public static final PbrTextureRuntimeProperties DEFAULT =
            new PbrTextureRuntimeProperties(1.0f, 1.0f, 1.0f, 1.0f, 0.0f);

    public PbrTextureRuntimeProperties {
        baselineEmissionScale = finiteRange(
                baselineEmissionScale, 0.0f, 8.0f, "baselineEmissionScale"
        );
        labPbrEmissionScale = finiteRange(
                labPbrEmissionScale, 0.0f, 8.0f, "labPbrEmissionScale"
        );
        roughnessScale = finiteRange(roughnessScale, 0.0f, 4.0f, "roughnessScale");
        normalStrength = finiteRange(normalStrength, 0.0f, 4.0f, "normalStrength");
        alphaCutoff = finiteRange(alphaCutoff, 0.0f, 1.0f, "alphaCutoff");
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
