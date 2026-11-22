package dev.totem.lumen.mixin;

import dev.totem.lumen.integration.EntityRgbLighting;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(SubmitNodeCollection.class)
public abstract class EntityModelRgbMixin {
    // Ordinal zero is the material submission. Vanilla constructs a separate outline afterward.
    @ModifyArgs(method = "submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V",
            at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$Submit;<init>(Lnet/minecraft/client/renderer/rendertype/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/model/Model;Ljava/lang/Object;IIILnet/minecraft/client/renderer/texture/UvMapping;Lcom/mojang/blaze3d/vertex/PoseStack$Pose;)V"))
    private void totemLumen$lightModel(Args args) {
        var sample = EntityRgbLighting.current();
        RenderType type = args.get(0);
        int light = args.get(4);
        if (sample == null || !sample.applies(type, light)) return;
        args.set(4, LightCoordsUtil.FULL_BRIGHT);
        if (sample.camera() == null) {
            args.set(6, ARGB.multiply(args.<Integer>get(6), sample.tint()));
        } else {
            args.set(7, sample.spatialMapping(args.get(7)));
        }
    }
}
