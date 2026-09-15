package dev.totem.lumen.integration;

import dev.totem.lumen.material.MaterialDefinition;
import dev.totem.lumen.material.MaterialFlags;
import dev.totem.lumen.world.LightingWorldRule;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Minecraft-facing adapter from BlockState to Totem Lumen-owned material metadata.
 *
 * <p>P15 enables transmission only for vanilla clear/stained glass and panes. Fluids remain merely
 * translucent until their own traversal/refraction milestone, and tinted glass intentionally stays
 * light-blocking to preserve its vanilla gameplay property.</p>
 */
public final class MinecraftMaterialResolver {
    private MinecraftMaterialResolver() {
    }

    public static MaterialDefinition resolve(BlockState state) {
        if (state.isAir()) {
            return MaterialDefinition.AIR;
        }

        String sourceId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        int emission = state.getLightEmission();
        boolean fluid = state.getBlock() instanceof LiquidBlock;
        boolean translucent = fluid
                || state.getBlock() instanceof TransparentBlock
                || sourceId.endsWith("glass_pane");
        boolean transmissiveGlass = isTransmissiveGlass(sourceId);
        boolean stainedGlass = sourceId.contains("_stained_glass");
        boolean cutout = !translucent && !state.canOcclude();

        int flags = 0;
        if (translucent) {
            flags |= MaterialFlags.TRANSLUCENT;
        } else if (cutout) {
            flags |= MaterialFlags.CUTOUT;
        } else {
            flags |= MaterialFlags.OPAQUE;
        }
        if (fluid) {
            flags |= MaterialFlags.FLUID;
        }
        if (transmissiveGlass) {
            flags |= MaterialFlags.TRANSMISSIVE;
        }
        if (emission > 0) {
            flags |= MaterialFlags.EMISSIVE;
        }

        float roughness = 0.8f;
        float opacity = 1.0f;
        float ior = 1.0f;

        if (translucent) {
            roughness = 0.08f;
            if (fluid) {
                opacity = 0.08f;
            } else if (stainedGlass) {
                opacity = 0.12f;
            } else {
                opacity = 0.05f;
            }
            ior = sourceId.equals("minecraft:water") ? 1.333f : 1.5f;
        }

        float[] emissionColor = baselineEmissionColor(sourceId, emission);
        LightingWorldRule serverRule = emission > 0
                ? ClientLightingWorldRules.ruleFor(sourceId)
                : null;
        if (serverRule != null) {
            emissionColor = new float[]{
                    serverRule.emissionR(),
                    serverRule.emissionG(),
                    serverRule.emissionB()
            };
        }

        float[] transmissionColor = transmissiveGlass
                ? baselineTransmissionColor(sourceId)
                : new float[]{1.0f, 1.0f, 1.0f};
        return new MaterialDefinition(
                sourceId,
                flags,
                emission,
                emissionColor[0],
                emissionColor[1],
                emissionColor[2],
                roughness,
                0.0f,
                opacity,
                ior,
                transmissionColor[0],
                transmissionColor[1],
                transmissionColor[2]
        );
    }

    private static boolean isTransmissiveGlass(String sourceId) {
        if (sourceId.equals("minecraft:tinted_glass")) {
            return false;
        }
        return sourceId.equals("minecraft:glass")
                || sourceId.equals("minecraft:glass_pane")
                || sourceId.endsWith("_stained_glass")
                || sourceId.endsWith("_stained_glass_pane");
    }

    /**
     * Coarse spectral tint used until resource-pack texture/PBR data owns transmission color.
     * Values are deliberately softened so a single pane tints light without crushing two channels.
     */
    private static float[] baselineTransmissionColor(String sourceId) {
        if (sourceId.startsWith("minecraft:light_blue_")) return new float[]{0.48f, 0.76f, 1.00f};
        if (sourceId.startsWith("minecraft:light_gray_")) return new float[]{0.76f, 0.78f, 0.80f};
        if (sourceId.startsWith("minecraft:white_")) return new float[]{1.00f, 0.98f, 0.95f};
        if (sourceId.startsWith("minecraft:orange_")) return new float[]{1.00f, 0.58f, 0.28f};
        if (sourceId.startsWith("minecraft:magenta_")) return new float[]{0.90f, 0.42f, 0.92f};
        if (sourceId.startsWith("minecraft:yellow_")) return new float[]{1.00f, 0.88f, 0.32f};
        if (sourceId.startsWith("minecraft:lime_")) return new float[]{0.58f, 0.92f, 0.34f};
        if (sourceId.startsWith("minecraft:pink_")) return new float[]{1.00f, 0.58f, 0.74f};
        if (sourceId.startsWith("minecraft:gray_")) return new float[]{0.48f, 0.50f, 0.52f};
        if (sourceId.startsWith("minecraft:cyan_")) return new float[]{0.34f, 0.78f, 0.82f};
        if (sourceId.startsWith("minecraft:purple_")) return new float[]{0.67f, 0.42f, 0.86f};
        if (sourceId.startsWith("minecraft:blue_")) return new float[]{0.38f, 0.48f, 0.92f};
        if (sourceId.startsWith("minecraft:brown_")) return new float[]{0.62f, 0.44f, 0.28f};
        if (sourceId.startsWith("minecraft:green_")) return new float[]{0.40f, 0.70f, 0.34f};
        if (sourceId.startsWith("minecraft:red_")) return new float[]{0.96f, 0.34f, 0.30f};
        if (sourceId.startsWith("minecraft:black_")) return new float[]{0.24f, 0.25f, 0.29f};
        return new float[]{1.0f, 1.0f, 1.0f};
    }

    /**
     * Baseline vanilla tint approximation. Server world rules override this color when present;
     * keeping the fallback here preserves sensible lighting on servers that do not define a rule.
     */
    private static float[] baselineEmissionColor(String sourceId, int emission) {
        if (emission <= 0) {
            return new float[]{0.0f, 0.0f, 0.0f};
        }
        if (sourceId.contains("soul_")) {
            return new float[]{0.28f, 0.78f, 1.0f};
        }
        if (sourceId.contains("redstone_torch")) {
            return new float[]{1.0f, 0.18f, 0.06f};
        }
        if (sourceId.contains("lava") || sourceId.contains("magma")) {
            return new float[]{1.0f, 0.32f, 0.08f};
        }
        if (sourceId.contains("ochre_froglight")) {
            return new float[]{1.0f, 0.76f, 0.35f};
        }
        if (sourceId.contains("verdant_froglight")) {
            return new float[]{0.58f, 1.0f, 0.62f};
        }
        if (sourceId.contains("pearlescent_froglight")) {
            return new float[]{1.0f, 0.62f, 0.92f};
        }
        if (sourceId.contains("sea_lantern") || sourceId.contains("conduit")) {
            return new float[]{0.62f, 0.90f, 1.0f};
        }
        if (sourceId.contains("end_rod")) {
            return new float[]{0.88f, 0.84f, 1.0f};
        }
        if (sourceId.contains("glowstone")) {
            return new float[]{1.0f, 0.78f, 0.42f};
        }
        if (sourceId.contains("shroomlight")) {
            return new float[]{1.0f, 0.48f, 0.18f};
        }
        if (sourceId.contains("fire")
                || sourceId.contains("torch")
                || sourceId.contains("lantern")
                || sourceId.contains("campfire")
                || sourceId.contains("jack_o_lantern")) {
            return new float[]{1.0f, 0.55f, 0.22f};
        }
        return new float[]{1.0f, 0.86f, 0.66f};
    }
}
