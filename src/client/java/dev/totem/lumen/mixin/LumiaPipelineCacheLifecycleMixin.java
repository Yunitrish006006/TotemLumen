package dev.totem.lumen.mixin;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.totem.lumen.integration.LumiaPreparation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderSystem.class)
abstract class LumiaPipelineCacheLifecycleMixin {
    @Inject(method = "setCurrentPipelineCache", at = @At("HEAD"))
    private static void totemLumen$replaceCache(PipelineCache next, CallbackInfoReturnable<PipelineCache> ci) {
        LumiaPreparation.replaceCache(next);
    }

    @Inject(method = "shutdownRenderer", at = @At("HEAD"))
    private static void totemLumen$releaseCompiler(CallbackInfo ci) {
        LumiaPreparation.replaceCache(null);
    }
}
