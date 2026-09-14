package dev.totem.lumen.mixin;

import dev.totem.lumen.integration.SceneExtractionBridge;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors Minecraft 26.2's canonical client renderer block-dirty notification into Totem Lumen.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Inject(method = "blockChanged", at = @At("HEAD"))
    private void totemLumen$onBlockChanged(
            BlockGetter level,
            BlockPos pos,
            BlockState oldState,
            BlockState newState,
            int updateFlags,
            CallbackInfo ci
    ) {
        SceneExtractionBridge.onBlockChanged(pos, updateFlags);
    }
}
