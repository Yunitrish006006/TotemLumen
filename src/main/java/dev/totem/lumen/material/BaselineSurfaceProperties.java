package dev.totem.lumen.material;

/**
 * Coarse built-in surface-response fallback used until resource-pack/LabPBR material metadata owns
 * roughness and metallic response. These values are intentionally conservative and deterministic.
 */
public final class BaselineSurfaceProperties {
    private BaselineSurfaceProperties() {
    }

    public static SurfaceProperties forBlock(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return SurfaceProperties.DEFAULT;
        }

        String path = sourceId;
        int separator = sourceId.indexOf(':');
        if (separator >= 0 && separator + 1 < sourceId.length()) {
            path = sourceId.substring(separator + 1);
        }

        if (path.equals("water")) return new SurfaceProperties(0.04f, 0.00f);
        if (path.equals("lava")) return new SurfaceProperties(0.32f, 0.00f);
        if (path.equals("glass") || path.equals("glass_pane") || path.contains("_stained_glass")) {
            return new SurfaceProperties(0.05f, 0.00f);
        }
        if (path.equals("tinted_glass")) return new SurfaceProperties(0.08f, 0.00f);
        if (path.equals("ice") || path.equals("packed_ice") || path.equals("blue_ice") || path.equals("frosted_ice")) {
            return new SurfaceProperties(0.12f, 0.00f);
        }
        if (path.equals("slime_block") || path.equals("honey_block")) {
            return new SurfaceProperties(0.28f, 0.00f);
        }

        if (path.equals("gold_block")) return new SurfaceProperties(0.22f, 0.95f);
        if (path.equals("iron_block")) return new SurfaceProperties(0.30f, 0.88f);
        if (path.equals("netherite_block")) return new SurfaceProperties(0.38f, 0.82f);
        if (path.equals("raw_gold_block")) return new SurfaceProperties(0.55f, 0.62f);
        if (path.equals("raw_iron_block")) return new SurfaceProperties(0.62f, 0.48f);
        if (path.equals("raw_copper_block")) return new SurfaceProperties(0.52f, 0.55f);

        String copperPath = path.startsWith("waxed_") ? path.substring("waxed_".length()) : path;
        if (copperPath.contains("copper")) {
            if (copperPath.contains("oxidized")) return new SurfaceProperties(0.72f, 0.12f);
            if (copperPath.contains("weathered")) return new SurfaceProperties(0.58f, 0.32f);
            if (copperPath.contains("exposed")) return new SurfaceProperties(0.44f, 0.56f);
            if (copperPath.equals("copper_block") || copperPath.contains("cut_copper")) {
                return new SurfaceProperties(0.32f, 0.78f);
            }
        }

        if (path.equals("diamond_block")
                || path.equals("emerald_block")
                || path.equals("lapis_block")) {
            return new SurfaceProperties(0.20f, 0.00f);
        }
        if (path.equals("obsidian") || path.equals("crying_obsidian")) {
            return new SurfaceProperties(0.22f, 0.00f);
        }
        if (path.equals("amethyst_block") || path.equals("budding_amethyst")) {
            return new SurfaceProperties(0.26f, 0.00f);
        }
        if (path.startsWith("polished_")
                || path.startsWith("smooth_")
                || path.contains("quartz_block")) {
            return new SurfaceProperties(0.30f, 0.00f);
        }

        return SurfaceProperties.DEFAULT;
    }
}
