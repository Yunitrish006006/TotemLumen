package dev.totem.lumen.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.totem.lumen.integration.LumiaPipelines;
import net.minecraft.client.renderer.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(ShaderManager.class)
abstract class LumiaShaderReloadMixin {
    @ModifyExpressionValue(method = "lambda$reload$1", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/RenderPipelines;optionalPipelines()Ljava/util/List;"), require = 1)
    private static List<RenderPipeline> totemLumen$prepareActivePipelines(List<RenderPipeline> optional) {
        return LumiaPipelines.optionalForReload(optional);
    }
}
