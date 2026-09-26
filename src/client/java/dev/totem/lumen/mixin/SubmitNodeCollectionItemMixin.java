package dev.totem.lumen.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.totem.lumen.integration.EntityRenderGeometryCapture;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures renderer-resolved dropped-item quads into the active P17 entity scope. */
@Mixin(SubmitNodeCollection.class)
public abstract class SubmitNodeCollectionItemMixin {
    private static final String SUBMIT_ITEM_26_3 =
            "submitItem(Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/world/item/ItemDisplayContext;III[I"
                    + "Lnet/minecraft/client/resources/model/geometry/ItemQuads;"
                    + "Lnet/minecraft/client/renderer/item/ItemStackRenderState$FoilType;)V";

    @Inject(method = SUBMIT_ITEM_26_3, at = @At("HEAD"), require = 1)
    private void totemLumen$captureItem(
            PoseStack poseStack,
            ItemDisplayContext displayContext,
            int light,
            int overlay,
            int outlineColor,
            int[] tintLayers,
            ItemQuads quads,
            ItemStackRenderState.FoilType foilType,
            CallbackInfo ci
    ) {
        EntityRenderGeometryCapture.captureItem(poseStack, quads.all());
    }
}
