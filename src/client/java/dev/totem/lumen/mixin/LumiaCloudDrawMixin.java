package dev.totem.lumen.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.totem.lumen.integration.LumiaActors;
import dev.totem.lumen.integration.LumiaPipelines;
import dev.totem.lumen.integration.LumiaScene;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CloudRenderer.class)
public abstract class LumiaCloudDrawMixin {
    private static final String DRAW = "render("
            + "Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V";

    @ModifyVariable(method = DRAW, at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private RenderPipeline totemLumen$selectCloudMaterial(RenderPipeline original) {
        return LumiaActors.active() ? LumiaActors.variant(original) : original;
    }

    @Inject(method = DRAW, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setPipeline(Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;)V"))
    private void totemLumen$bindCloudScene(RenderPass pass, RenderPipeline selected, CallbackInfo ci) {
        if (selected instanceof LumiaPipelines) LumiaScene.bind(pass);
    }
}
