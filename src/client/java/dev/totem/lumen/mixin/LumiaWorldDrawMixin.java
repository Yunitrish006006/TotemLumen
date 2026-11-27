package dev.totem.lumen.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.totem.lumen.integration.LumiaActors;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

/** World and first-person rendering finish before GUI models and inventory previews are drawn. */
@Mixin(GameRenderer.class)
public abstract class LumiaWorldDrawMixin {
    @WrapMethod(method = "renderLevel")
    private void totemLumen$scopeWorldDraw(Operation<Void> original) {
        boolean previous = LumiaActors.setWorldDraw(true);
        try {
            original.call();
        } finally {
            LumiaActors.setWorldDraw(previous);
        }
    }
}
