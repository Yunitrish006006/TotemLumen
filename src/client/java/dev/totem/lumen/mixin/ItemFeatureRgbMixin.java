package dev.totem.lumen.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.totem.lumen.integration.ItemSubmitRgb;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemFeatureRenderer.class)
public abstract class ItemFeatureRgbMixin {
    @ModifyExpressionValue(method = "prepareMainSubmit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit;lightCoords()I"))
    private int totemLumen$itemLight(int original, ItemFeatureRenderer.Submit submit) {
        return ((ItemSubmitRgb) (Object) submit).totemLumen$worldTint() == 0 ? original : LightCoordsUtil.FULL_BRIGHT;
    }

    @ModifyExpressionValue(method = "prepareMainSubmit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer;getLayerColorSafe([ILnet/minecraft/client/resources/model/geometry/BakedQuad$MaterialInfo;)I"))
    private int totemLumen$itemColor(int original, ItemFeatureRenderer.Submit submit) {
        int tint = ((ItemSubmitRgb) (Object) submit).totemLumen$worldTint();
        return tint == 0 ? original : ARGB.multiply(original, tint);
    }
}
