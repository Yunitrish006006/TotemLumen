package dev.totem.lumen.mixin;

import dev.totem.lumen.integration.FluidRenderGeometryCache;
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
 * section snapshot queue and invalidates exact fluid geometry that depends on the changed cell.
 *
 * <p>ClientLevel.sendBlockUpdated is the canonical client-side bridge that forwards a block
 * state change to LevelRenderer. Fluid corner heights and side visibility depend on neighboring
 * cells, so P14E removes a bounded 3x3x3 capture neighborhood until Minecraft's section mesher
 * emits the replacement fluid faces.</p>
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
        FluidRenderGeometryCache.invalidateNeighborhood(pos);
        SceneExtractionBridge.onBlockChanged(pos, updateFlags);
    }
}
