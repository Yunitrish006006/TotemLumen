package dev.totem.lumen.integration;

import dev.totem.lumen.material.MaterialDefinition;
import dev.totem.lumen.material.MaterialFlags;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * P2 adapter from Minecraft BlockState to Totem Lumen-owned material metadata.
 *
 * <p>This is deliberately conservative. LabPBR/resource-pack texture semantics and specialized
 * vanilla overrides arrive later; the first goal is deterministic voxel/material classification.</p>
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
        if (emission > 0) {
            flags |= MaterialFlags.EMISSIVE;
        }

        float roughness = 0.8f;
        float opacity = 1.0f;
        float ior = 1.0f;

        if (translucent) {
            roughness = 0.08f;
            opacity = fluid ? 0.08f : 0.18f;
            ior = sourceId.equals("minecraft:water") ? 1.333f : 1.5f;
        }

        float[] emissionColor = baselineEmissionColor(sourceId, emission);
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
                ior
        );
    }

    /**
     * Baseline vanilla tint approximation until texture/resource-pack emissive data is available.
     * Keeping this on the extraction side means the Vulkan shader consumes generic material data
     * rather than hard-coding Minecraft block identifiers.
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
