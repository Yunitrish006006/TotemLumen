package dev.totem.lumen.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.totem.lumen.integration.BlockEntityRenderGeometryCapture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Establishes one render-thread P14D capture scope around each block-entity renderer submission. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @Inject(method = "submit", at = @At("HEAD"))
    private <S extends BlockEntityRenderState> void totemLumen$beginBlockEntityCapture(
            S renderState,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState cameraState,
            CallbackInfo ci
    ) {
        BlockEntityRenderGeometryCapture.begin(renderState, poseStack);
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private <S extends BlockEntityRenderState> void totemLumen$endBlockEntityCapture(
            S renderState,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState cameraState,
            CallbackInfo ci
    ) {
        BlockEntityRenderGeometryCapture.end();
    }
}
