package dev.totem.lumen.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.totem.lumen.integration.LumiaActors;
import dev.totem.lumen.integration.LumiaScene;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(QuadParticleFeatureRenderer.class)
public abstract class LumiaParticleDrawMixin {
    @ModifyArg(method = "drawLayers", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"), index = 0)
    private static RenderPipeline totemLumen$particleMaterial(RenderPipeline original,
            @Share("lumia") LocalBooleanRef selected) {
        var pipeline = LumiaActors.active() ? LumiaActors.variant(original) : original;
        selected.set(pipeline != original);
        return pipeline;
    }

    @Inject(method = "drawLayers", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setPipeline(Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;)V"))
    private static void totemLumen$bindParticleScene(CallbackInfo ci, @Local(argsOnly = true) RenderPass pass,
            @Share("lumia") LocalBooleanRef selected) {
        if (selected.get()) LumiaScene.bind(pass);
    }
}
