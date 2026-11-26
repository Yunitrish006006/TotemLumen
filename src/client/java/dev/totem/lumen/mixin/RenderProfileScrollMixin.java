package dev.totem.lumen.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.totem.lumen.TotemLumenClient;
import net.minecraft.client.MouseHandler;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MouseHandler.class)
abstract class RenderProfileScrollMixin {
    // Minecraft has already routed screen input and accumulated fractional wheel movement.
    @ModifyExpressionValue(method = "onScroll", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/ScrollWheelHandler;onMouseScroll(DD)Lorg/joml/Vector2i;"))
    private Vector2i switchLightingProfile(Vector2i scroll) {
        return TotemLumenClient.scrollRenderProfile(scroll.y) ? new Vector2i() : scroll;
    }
}
