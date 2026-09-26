package dev.totem.lumen.mixin;

import dev.totem.lumen.integration.HeldItemGlow;
import dev.totem.lumen.integration.ItemStackRenderStateGlowAccess;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Carries the source item's vanilla emission into the resolved item render state. */
@Mixin(ItemModelResolver.class)
public abstract class ItemModelResolverGlowMixin {
    @Inject(method = "appendItemLayers", at = @At("HEAD"))
    private void totemLumen$markEmissiveItem(
            ItemStackRenderState state,
            ItemStack stack,
            ItemDisplayContext displayContext,
            Level level,
            ItemOwner owner,
            int seed,
            CallbackInfo ci
    ) {
        ((ItemStackRenderStateGlowAccess) state).totemLumen$setEmissiveItem(HeldItemGlow.isEmissive(stack));
    }
}
