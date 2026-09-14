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

        return new MaterialDefinition(
                sourceId,
                flags,
                emission,
                roughness,
                0.0f,
                opacity,
                ior
        );
    }
}
