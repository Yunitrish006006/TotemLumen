package dev.totem.lumen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.totem.lumen.integration.EntityRgbLighting;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
public abstract class WorldEntityRgbMixin {
    @WrapOperation(method = "submitEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"))
    private void totemLumen$submitWorldEntity(EntityRenderDispatcher dispatcher, EntityRenderState state,
            CameraRenderState camera, double x, double y, double z, PoseStack poses,
            SubmitNodeCollector collector, Operation<Void> original) {
        var previous = EntityRgbLighting.current();
        var lighting = ((EntityRgbLighting.State) state).totemLumen$getLighting();
        if (lighting != null) lighting = lighting.forWorld(camera.pos);
        if (lighting == previous) {
            original.call(dispatcher, state, camera, x, y, z, poses, collector);
            return;
        }
        EntityRgbLighting.select(lighting);
        try {
            original.call(dispatcher, state, camera, x, y, z, poses, collector);
        } finally {
            EntityRgbLighting.select(previous);
        }
    }
}
