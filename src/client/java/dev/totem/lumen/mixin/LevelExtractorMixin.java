package dev.totem.lumen.mixin;

import dev.totem.lumen.integration.SceneExtractionBridge;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors Minecraft 26.2's canonical renderer dirty notification into Totem Lumen's scene queue.
 *
 * <p>The mixin copies primitive coordinates immediately; no Minecraft world/block objects are retained.</p>
 */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorMixin {
    @Inject(method = "blockChanged", at = @At("HEAD"))
    private void totemLumen$onBlockChanged(BlockPos pos, int updateFlags, CallbackInfo ci) {
        SceneExtractionBridge.onBlockChanged(pos, updateFlags);
    }
}
