package dev.totem.lumen.mixin;

import dev.totem.lumen.gameplay.light.ServerGameplayLightingManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds every changed server BlockState into the bounded gameplay-light engine. */
@Mixin(Level.class)
abstract class LevelBlockChangeMixin {
    @Inject(method = "setBlocksDirty", at = @At("TAIL"))
    private void totemLumen$onBlockChanged(
            BlockPos pos,
            BlockState oldState,
            BlockState newState,
            CallbackInfo ci
    ) {
        if ((Object) this instanceof ServerLevel serverLevel) {
            ServerGameplayLightingManager.onBlockChanged(serverLevel, pos, oldState, newState);
        }
    }
}
