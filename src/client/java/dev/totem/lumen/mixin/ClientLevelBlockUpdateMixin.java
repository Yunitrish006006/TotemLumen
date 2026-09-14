package dev.totem.lumen.mixin;

import dev.totem.lumen.integration.SceneExtractionBridge;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Invalidates Totem Lumen section snapshots when the client world applies a block-state change.
 *
 * <p>The live renderer keys GPU uploads off section revisions. Without this hook, placing or
 * breaking blocks can leave the CPU scene unchanged until a larger lifecycle event (for example,
 * leaving and re-entering the world) forces fresh section extraction.</p>
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelBlockUpdateMixin {
    @Inject(method = "setBlock", at = @At("RETURN"))
    private void totemLumen$markSectionDirty(
            BlockPos pos,
            BlockState state,
            int flags,
            int recursionLeft,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (cir.getReturnValueZ()) {
            SceneExtractionBridge.markSectionDirty(pos);
        }
    }
}
