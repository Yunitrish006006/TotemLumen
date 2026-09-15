package dev.totem.lumen.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.totem.lumen.integration.BlockEntityRenderGeometryCapture;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
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
    @Inject(method = "submitModel", at = @At("HEAD"), require = 0)
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

    @Inject(method = "submitModel", at = @At("HEAD"), require = 0)
    private <S> void totemLumen$captureModel(
            Model<? super S> model,
            S state,
            PoseStack poseStack,
            RenderType renderType,
            int light,
            int overlay,
            int color,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
            CallbackInfo ci
    ) {
        BlockEntityRenderGeometryCapture.captureModel(model, state, poseStack);
    }

    @Inject(method = "submitModelPart", at = @At("HEAD"), require = 0)
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
