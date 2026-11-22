package dev.totem.lumen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.totem.lumen.gameplay.light.PackedRgbLight;
import dev.totem.lumen.integration.ClientGameplayLightField;
import dev.totem.lumen.render.RendererSettings;
import net.minecraft.client.gui.Hud;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Hud.class)
public abstract class RgbVignetteMixin {
    @WrapOperation(method = "updateVignetteBrightness", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getMaxLocalRawBrightness(Lnet/minecraft/core/BlockPos;)I"))
    private int totemLumen$eyeLight(Level level, BlockPos eye, Operation<Integer> original) {
        if (RendererSettings.renderProfile() != RendererSettings.RenderProfile.MINECRAFT_RGB)
            return original.call(level, eye);
        int block = PackedRgbLight.maxChannel(ClientGameplayLightField.packedAtCurrentDimension(eye));
        return Math.max(block, level.getEffectiveSkyBrightness(eye));
    }
}
