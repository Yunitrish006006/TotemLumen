package dev.totem.lumen.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.totem.lumen.integration.LumiaActors;
import dev.totem.lumen.integration.LumiaScene;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PreparedRenderType.class)
public abstract class LumiaActorDrawMixin {
    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void totemLumen$skipVanillaBlob(StagedVertexBuffer.ExecuteInfo geometry, RenderPass pass,
            RenderPipeline pipeline, CallbackInfo ci) {
        if (LumiaActors.active() && LumiaActors.nativeShadow(pipeline)) ci.cancel();
    }

    @ModifyArg(method = "draw", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"), index = 0)
    private RenderPipeline totemLumen$worldMaterial(RenderPipeline original,
            @Share("lumia") LocalBooleanRef selected) {
        var pipeline = LumiaActors.active() ? LumiaActors.variant(original) : original;
        selected.set(pipeline != original);
        return pipeline;
    }

    @Inject(method = "draw", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setPipeline(Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;)V"))
    private void totemLumen$bindScene(StagedVertexBuffer.ExecuteInfo geometry, RenderPass pass,
            RenderPipeline pipeline, CallbackInfo ci, @Share("lumia") LocalBooleanRef selected) {
        if (selected.get()) LumiaScene.bind(pass);
    }
}
