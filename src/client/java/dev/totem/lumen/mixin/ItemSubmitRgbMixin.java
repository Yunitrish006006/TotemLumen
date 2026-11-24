package dev.totem.lumen.mixin;

import dev.totem.lumen.integration.EntityRgbLighting;
import dev.totem.lumen.integration.ItemSubmitRgb;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemFeatureRenderer.Submit.class)
public abstract class ItemSubmitRgbMixin implements ItemSubmitRgb {
    @Shadow public abstract ItemDisplayContext displayContext();
    @Shadow public abstract int outlineColor();
    @Shadow public abstract int lightCoords();
    @Unique private int totemLumen$tint;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void totemLumen$captureTint(CallbackInfo ci) {
        if (displayContext() != ItemDisplayContext.GROUND || outlineColor() != 0) return;
        var light = EntityRgbLighting.current();
        if (light != null && light.lightCoords() == lightCoords()) totemLumen$tint = light.tint();
    }

    @Override
    public int totemLumen$worldTint() {
        return totemLumen$tint;
    }
}
