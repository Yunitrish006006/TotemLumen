package dev.totem.lumen.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.totem.lumen.integration.BlockEntityRenderGeometryCapture;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures model/model-part commands only while a BlockEntityRenderDispatcher P14D scope is active. */
@Mixin(SubmitNodeStorage.class)
public abstract class SubmitNodeStorageBlockEntityMixin {
    @Inject(
            method = "submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
            at = @At("HEAD"),
            require = 0
    )
    private <S> void totemLumen$captureModel(
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

    @Inject(
            method = "submitModelPart(Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ZZILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;I)V",
            at = @At("HEAD"),
            require = 0
    )
    private void totemLumen$captureModelPart(
            ModelPart modelPart,
            PoseStack poseStack,
            RenderType renderType,
            int light,
            int overlay,
            TextureAtlasSprite sprite,
            boolean sheeted,
            boolean hasFoil,
            int tintedColor,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
            int outlineColor,
            CallbackInfo ci
    ) {
        BlockEntityRenderGeometryCapture.captureModelPart(modelPart, poseStack);
    }
}
