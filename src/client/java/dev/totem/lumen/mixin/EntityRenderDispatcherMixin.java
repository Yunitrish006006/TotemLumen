package dev.totem.lumen.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.totem.lumen.integration.EntityRenderGeometryCapture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Establishes one P17 capture scope around each Minecraft 26.3 entity renderer submission. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    private static final String SUBMIT_26_2 =
            "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"
                    + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDD"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector;)V";

    @Inject(method = SUBMIT_26_2, at = @At("HEAD"), require = 1)
    private <S extends EntityRenderState> void totemLumen$beginEntityCapture(
            S renderState,
            CameraRenderState camera,
            double x,
            double y,
            double z,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            CallbackInfo ci
    ) {
        EntityRenderGeometryCapture.begin(renderState, poseStack, x, y, z);
    }

    @Inject(method = SUBMIT_26_2, at = @At("RETURN"), require = 1)
    private <S extends EntityRenderState> void totemLumen$endEntityCapture(
            S renderState,
            CameraRenderState camera,
            double x,
            double y,
            double z,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            CallbackInfo ci
    ) {
        EntityRenderGeometryCapture.end();
    }
}
