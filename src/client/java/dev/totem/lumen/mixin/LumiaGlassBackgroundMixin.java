package dev.totem.lumen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.totem.lumen.integration.LumiaGlassBackground;
import dev.totem.lumen.integration.LumiaScene;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;

@Mixin(LevelRenderer.class)
abstract class LumiaGlassBackgroundMixin {
    @Shadow @Final private LevelTargetBundle targets;

    // Classic normally draws into the still-open opaque pass. Run the same draw after
    // that pass closes so the two attachment copies are legal on Vulkan and MoltenVK.
    @WrapOperation(method = "lambda$addMainPass$0", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;executeClassicTransparency(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
    private void totemLumen$deferClassic(LevelRenderer renderer, ChunkSectionsToRender chunks,
            FeatureRenderDispatcher.PreparedFrame features, RenderPass opaquePass, Operation<Void> draw,
            @Share("lumiaClassic") LocalRef<Runnable> deferred) {
        if (!LumiaScene.ready()) {
            draw.call(renderer, chunks, features, opaquePass);
            return;
        }
        var target = targets.main.get();
        deferred.set(() -> {
            LumiaGlassBackground.capture(target);
            try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "Lumia classic transparency", target.getColorTextureView(), Optional.empty(),
                    target.getDepthTextureView(), OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                draw.call(renderer, chunks, features, pass);
            } finally {
                LumiaGlassBackground.endTransparency();
            }
        });
    }

    @Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;executeOutline(Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;)V"))
    private void totemLumen$drawDeferredClassic(CallbackInfo ci, @Share("lumiaClassic") LocalRef<Runnable> deferred) {
        if (deferred.get() != null) deferred.get().run();
    }

    @WrapOperation(method = "lambda$addMainPass$0", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;executeOit(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;)V"))
    private void totemLumen$captureBeforeOit(LevelRenderer renderer, ChunkSectionsToRender chunks,
            FeatureRenderDispatcher.PreparedFrame features, Operation<Void> draw) {
        LumiaGlassBackground.capture(targets.main.get());
        try {
            draw.call(renderer, chunks, features);
        } finally {
            LumiaGlassBackground.endTransparency();
        }
    }
}
