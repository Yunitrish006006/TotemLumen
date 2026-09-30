package dev.totem.lumen.mixin;

import dev.totem.lumen.integration.RgbFrameMetrics;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class RgbFrameMetricsMixin {
    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void totemLumen$frameStart(boolean renderLevel, CallbackInfo ci) {
        RgbFrameMetrics.frameStart((Minecraft) (Object) this, renderLevel);
    }

    @Inject(method = "renderFrame", at = @At("TAIL"))
    private void totemLumen$frameEnd(boolean renderLevel, CallbackInfo ci) {
        RgbFrameMetrics.frameEnd((Minecraft) (Object) this, renderLevel);
    }
}
