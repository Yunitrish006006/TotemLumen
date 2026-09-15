package dev.totem.lumen.integration;

import dev.totem.lumen.scene.BlockGeometryCode;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Minecraft-facing block-state classifier for Totem Lumen's compact block-local geometry ABI.
 *
 * <p>P14A intentionally starts with slabs only. Unknown/non-air blocks remain on the proven
 * full-cube fast path until their dedicated local primitive representation is implemented.</p>
 */
public final class MinecraftGeometryResolver {
    private MinecraftGeometryResolver() {
    }

    public static int geometryCode(BlockState state) {
        if (state.isAir()) {
            return BlockGeometryCode.FULL_CUBE;
        }
        if (state.getBlock() instanceof SlabBlock) {
            SlabType type = state.getValue(SlabBlock.TYPE);
            return switch (type) {
                case BOTTOM -> BlockGeometryCode.SLAB_BOTTOM;
                case TOP -> BlockGeometryCode.SLAB_TOP;
                case DOUBLE -> BlockGeometryCode.FULL_CUBE;
            };
        }
        return BlockGeometryCode.FULL_CUBE;
    }
}
