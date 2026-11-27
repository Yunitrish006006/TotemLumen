package dev.totem.lumen.mixin;

import dev.totem.lumen.integration.ItemStackRenderStateGlowAccess;
import dev.totem.lumen.integration.LumiaActors;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.util.LightCoordsUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes luminous item models use full-bright lightmap coordinates without changing their texture. */
@Mixin(ItemStackRenderState.class)
public abstract class ItemStackRenderStateGlowMixin implements ItemStackRenderStateGlowAccess {
    @Unique
    private boolean totemLumen$emissiveItem;

    @Override
    public void totemLumen$setEmissiveItem(boolean emissive) {
        totemLumen$emissiveItem = emissive;
    }

    @Override
    public boolean totemLumen$isEmissiveItem() {
        return totemLumen$emissiveItem;
    }

    @Inject(method = "clear", at = @At("TAIL"))
    private void totemLumen$clearEmissiveMarker(CallbackInfo ci) {
        totemLumen$emissiveItem = false;
    }

    @ModifyArg(
            method = "submit",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState$LayerRenderState;"
                            + "submit(Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"
            ),
            index = 2
    )
    private int totemLumen$fullBrightEmissiveItem(int light) {
        if (!totemLumen$emissiveItem) return light;
        // Native sampling clamps coordinates above the lightmap. Lumia can distinguish an
        // emitting item from an ordinary item standing under full sky and block light.
        return LumiaActors.active() ? LightCoordsUtil.FULL_BRIGHT | LumiaActors.EMISSION_FLAG
                : LightCoordsUtil.FULL_BRIGHT;
    }
}
