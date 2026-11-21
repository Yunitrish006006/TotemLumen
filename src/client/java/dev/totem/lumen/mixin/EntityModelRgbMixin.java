package dev.totem.lumen.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.totem.lumen.integration.EntityRgbLighting;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SubmitNodeCollection.class)
public abstract class EntityModelRgbMixin {
    @WrapMethod(method = "submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V")
    private <S> void totemLumen$lightModel(Model<? super S> model, S state, PoseStack poses,
            RenderType type, int light, int overlay, int color, UvMapping sprite, int outline,
            Operation<Void> original) {
        var sample = EntityRgbLighting.current();
        if (sample != null && sample.applies(type, light)) {
            color = ARGB.multiply(color, sample.tint());
            light = LightCoordsUtil.FULL_BRIGHT;
        }
        original.call(model, state, poses, type, light, overlay, color, sprite, outline);
    }
}
