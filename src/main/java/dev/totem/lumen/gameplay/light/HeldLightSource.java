package dev.totem.lumen.gameplay.light;

import dev.totem.lumen.world.EffectiveLightingRules;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/** Server-owned emission rule for items in either player hand. */
public final class HeldLightSource {
    private HeldLightSource() {
    }

    public static char packedFor(ItemStack stack) {
        BlockState state = emittingState(stack);
        if (state == null) return 0;
        return GameplayLightSource.packedFor(state,
                EffectiveLightingRules.ruleFor(BuiltInRegistries.BLOCK.getKey(state.getBlock())));
    }

    public static BlockState emittingState(ItemStack stack) {
        if (stack.isEmpty()) return null;
        BlockState state;
        if (stack.is(Items.LAVA_BUCKET)) {
            state = net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState();
        } else if (stack.getItem() instanceof BlockItem blockItem) {
            state = blockItem.getBlock().defaultBlockState();
        } else {
            return null;
        }
        return state.getLightEmission() > 0 ? state : null;
    }
}
