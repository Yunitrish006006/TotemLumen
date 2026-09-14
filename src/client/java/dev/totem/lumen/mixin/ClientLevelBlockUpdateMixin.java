package dev.totem.lumen.mixin;

import dev.totem.lumen.integration.SceneExtractionBridge;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors Minecraft 26.2's client-world block update into Totem Lumen's high-priority
 * section snapshot queue.
 *
 * <p>ClientLevel.sendBlockUpdated is the canonical client-side bridge that forwards a block
 * state change to LevelRenderer. Hooking here avoids depending on renderer-internal method
 * signatures while still observing the same update before the renderer marks its section dirty.</p>
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelBlockUpdateMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void totemLumen$onBlockUpdated(
            BlockPos pos,
            BlockState oldState,
            BlockState newState,
            int updateFlags,
            CallbackInfo ci
    ) {
        SceneExtractionBridge.onBlockChanged(pos, updateFlags);
    }
}
