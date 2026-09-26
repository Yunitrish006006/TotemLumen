package dev.totem.lumen.integration;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Identifies held item models that should use the same full-bright surface treatment as emitters. */
public final class HeldItemGlow {
    private HeldItemGlow() {
    }

    public static boolean isEmissive(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (stack.is(Items.LAVA_BUCKET)) {
            return true;
        }
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock().defaultBlockState().getLightEmission() > 0;
    }
}
