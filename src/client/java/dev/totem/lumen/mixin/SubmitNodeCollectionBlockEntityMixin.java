package dev.totem.lumen.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.totem.lumen.integration.BlockEntityRenderGeometryCapture;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Covers renderers that submit through collector.order(...), which returns a SubmitNodeCollection. */
@Mixin(SubmitNodeCollection.class)
public abstract class SubmitNodeCollectionBlockEntityMixin {
    private static final String SUBMIT_MODEL_26_2 =
            "submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/rendertype/RenderType;III"
                    + "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;I"
                    + "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V";

    @Inject(method = SUBMIT_MODEL_26_2, at = @At("HEAD"), require = 1)
    private <S> void totemLumen$captureModelWithSprite(
            Model<? super S> model,
            S state,
            PoseStack poseStack,
            RenderType renderType,
            int light,
            int overlay,
            int color,
            TextureAtlasSprite sprite,
            int outlineColor,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
            CallbackInfo ci
    ) {
        BlockEntityRenderGeometryCapture.captureModel(model, state, poseStack);
    }
}
