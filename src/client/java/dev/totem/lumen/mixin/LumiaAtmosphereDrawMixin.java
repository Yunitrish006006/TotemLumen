package dev.totem.lumen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.totem.lumen.integration.LumiaActors;
import dev.totem.lumen.integration.LumiaScene;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin({CloudRenderer.class, WeatherEffectRenderer.class})
public abstract class LumiaAtmosphereDrawMixin {
    // In each renderer the private pipeline-taking render overload is the sole one
    // that binds a pipeline. Other render overloads delegate to it.
    @WrapOperation(method = "render", require = 1, allow = 1, at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setPipeline(Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;)V"))
    private void totemLumen$atmosphereMaterial(RenderPass pass, CompiledRenderPipeline vanilla,
            Operation<Void> original, @Local(argsOnly = true) RenderPipeline source) {
        var selected = LumiaActors.active() ? LumiaActors.variant(source) : source;
        if (selected == source) {
            original.call(pass, vanilla);
        } else {
            original.call(pass, RenderSystem.getCompiledPipeline(selected));
            LumiaScene.bind(pass);
        }
    }
}
