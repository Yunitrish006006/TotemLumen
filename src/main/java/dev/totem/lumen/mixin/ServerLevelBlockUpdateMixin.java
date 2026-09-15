package dev.totem.lumen.mixin;

import dev.totem.lumen.gameplay.light.ServerGameplayLightingManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds authoritative server BlockState changes into the bounded gameplay-light engine. */
@Mixin(ServerLevel.class)
abstract class ServerLevelBlockUpdateMixin {
    @Inject(method = "sendBlockUpdated", at = @At("TAIL"))
    private void totemLumen$onBlockUpdated(
            BlockPos pos,
            BlockState oldState,
            BlockState newState,
            int flags,
            CallbackInfo ci
    ) {
        ServerGameplayLightingManager.onBlockChanged(
                (ServerLevel) (Object) this,
                pos,
                oldState,
                newState
        );
    }
}
