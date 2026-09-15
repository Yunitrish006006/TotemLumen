package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;

/**
 * Small P13 tuning patch for the End environment baseline.
 *
 * <p>alpha.21 validated the dimension-aware environment path on Apple M4 / MoltenVK, but the End
 * baseline was visually too dark. Keep the cold-purple character while raising sky and surface
 * environment energy without touching Overworld, Nether, local lights or GI history semantics.</p>
 */
final class P13EndBrightnessPatch {
    private P13EndBrightnessPatch() {
    }

    static String apply(String source) {
        String oldSky = "return vec3(0.040, 0.024, 0.074) * (0.88 + 0.12 * up);";
        String newSky = "return vec3(0.050, 0.030, 0.093) * (0.88 + 0.12 * up);";
        if (!source.contains(oldSky)) {
            throw new IllegalStateException("P13 End brightness patch marker missing: sky radiance");
        }
        source = source.replace(oldSky, newSky);

        String oldSurface = "return albedo * vec3(0.070, 0.046, 0.115) * facing + emitted;";
        String newSurface = "return albedo * vec3(0.085, 0.056, 0.140) * facing + emitted;";
        if (!source.contains(oldSurface)) {
            throw new IllegalStateException("P13 End brightness patch marker missing: surface radiance");
        }
        source = source.replace(oldSurface, newSurface);

        TotemLumenClient.LOGGER.info(
                "P13 End environment tuning active: skyScale≈1.25, surfaceScale≈1.22, palette=cold-purple"
        );
        return source;
    }
}
