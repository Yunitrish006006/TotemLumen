package dev.totem.lumen.mixin;

import dev.totem.lumen.integration.LumiaActors;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.util.LightCoordsUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(AbstractSignRenderer.class)
public abstract class LumiaSignGlowMixin {
    @ModifyConstant(method = "submitSignText", constant = @Constant(intValue = LightCoordsUtil.FULL_BRIGHT))
    private int totemLumen$markGlowingSignText(int fullBright) {
        return LumiaActors.active() ? fullBright | LumiaActors.EMISSION_FLAG : fullBright;
    }
}
