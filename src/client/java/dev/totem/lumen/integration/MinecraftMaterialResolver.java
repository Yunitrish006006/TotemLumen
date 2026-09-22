package dev.totem.lumen.integration;

import dev.totem.lumen.gameplay.light.DefaultEmissionColors;
import dev.totem.lumen.gameplay.light.EmissionColor;
import dev.totem.lumen.material.BaselineSurfaceProperties;
import dev.totem.lumen.material.MaterialDefinition;
import dev.totem.lumen.material.MaterialFlags;
import dev.totem.lumen.material.SurfaceProperties;
import dev.totem.lumen.world.LightingWorldRule;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Minecraft-facing adapter from BlockState to Totem Lumen-owned material metadata.
 *
 * <p>P15 enables transmission only for vanilla clear/stained glass and panes. P16 gives the
 * existing roughness/metallic fields a deterministic built-in fallback profile until resource-pack
 * PBR metadata becomes authoritative for client surface appearance.</p>
 */
public final class MinecraftMaterialResolver {
    private MinecraftMaterialResolver() {
    }

    public static MaterialDefinition resolve(BlockState state) {
        if (state.isAir()) {
            return MaterialDefinition.AIR;
        }

        String sourceId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        BlockMaterialRuleRegistry.ensureLoaded(Minecraft.getInstance().getResourceManager());
        BlockMaterialRuleRegistry.Overrides runtimeRule =
                BlockMaterialRuleRegistry.overridesFor(sourceId);
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

        SurfaceProperties surface = BaselineSurfaceProperties.forBlock(sourceId);
        float roughness = runtimeRule.roughness() == null
                ? surface.roughness()
                : runtimeRule.roughness();
        float metallic = runtimeRule.metallic() == null
                ? surface.metallic()
                : runtimeRule.metallic();

        float opacity = 1.0f;
        float ior = 1.0f;
        if (translucent) {
            if (fluid) {
                opacity = 0.08f;
            } else if (stainedGlass) {
                opacity = 0.12f;
            } else {
                opacity = 0.05f;
            }
            ior = sourceId.equals("minecraft:water") ? 1.333f : 1.5f;
        }
        if (runtimeRule.opacity() != null) opacity = runtimeRule.opacity();
        if (runtimeRule.ior() != null) ior = runtimeRule.ior();

        EmissionColor emissionColor = DefaultEmissionColors.forBlock(sourceId, emission);
        if (runtimeRule.emissionR() != null
                && runtimeRule.emissionG() != null
                && runtimeRule.emissionB() != null) {
            emissionColor = new EmissionColor(
                    runtimeRule.emissionR(),
                    runtimeRule.emissionG(),
                    runtimeRule.emissionB()
            );
        }
        LightingWorldRule serverRule = emission > 0
                ? ClientLightingWorldRules.ruleFor(sourceId)
                : null;
        if (serverRule != null) {
            emissionColor = new EmissionColor(
                    serverRule.emissionR(),
                    serverRule.emissionG(),
                    serverRule.emissionB()
            );
        }

        float[] transmissionColor = transmissiveGlass
                ? baselineTransmissionColor(sourceId)
                : new float[]{1.0f, 1.0f, 1.0f};
        if (runtimeRule.transmissionR() != null
                && runtimeRule.transmissionG() != null
                && runtimeRule.transmissionB() != null) {
            transmissionColor = new float[]{
                    runtimeRule.transmissionR(),
                    runtimeRule.transmissionG(),
                    runtimeRule.transmissionB()
            };
        }

        float lightRadiusScale = runtimeRule.lightRadiusScale() == null
                ? 1.0f
                : runtimeRule.lightRadiusScale();
        float lightIntensityScale = runtimeRule.lightIntensityScale() == null
                ? 1.0f
                : runtimeRule.lightIntensityScale();
        float reflectionScale = runtimeRule.reflectionScale() == null
                ? 1.0f
                : runtimeRule.reflectionScale();
        int lightEmitterAnchor = torchEmitterAnchor(state, sourceId);

        return new MaterialDefinition(
                sourceId,
                flags,
                emission,
                emissionColor.red(),
                emissionColor.green(),
                emissionColor.blue(),
                roughness,
                metallic,
                opacity,
                ior,
                transmissionColor[0],
                transmissionColor[1],
                transmissionColor[2],
                lightRadiusScale,
                lightIntensityScale,
                reflectionScale,
                lightEmitterAnchor
        );
    }

    private static int torchEmitterAnchor(BlockState state, String sourceId) {
        if (!isTorchLike(sourceId)) {
            return 0;
        }

        float x = 0.5f;
        float y = 0.70f;
        float z = 0.5f;

        if (sourceId.contains("wall_torch")) {
            switch (propertyValue(state, "facing")) {
                case "north" -> z -= 0.27f;
                case "south" -> z += 0.27f;
                case "west" -> x -= 0.27f;
                case "east" -> x += 0.27f;
                default -> {
                    // Unknown/custom wall-torch state keeps the centered flame anchor.
                }
            }
        }

        return MaterialDefinition.pointLightEmitterAnchor(x, y, z);
    }

    private static boolean isTorchLike(String sourceId) {
        return sourceId.equals("minecraft:torch")
                || sourceId.endsWith("_torch");
    }

    private static String propertyValue(BlockState state, String name) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) {
                return propertyValue(state, property);
            }
        }
        return "";
    }

    private static <T extends Comparable<T>> String propertyValue(
            BlockState state,
            Property<T> property
    ) {
        return property.getName(state.getValue(property));
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
}
