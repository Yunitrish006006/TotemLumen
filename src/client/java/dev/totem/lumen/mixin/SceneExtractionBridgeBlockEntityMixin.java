package dev.totem.lumen.mixin;

import dev.totem.lumen.integration.BlockEntityRenderGeometryCache;
import dev.totem.lumen.integration.SceneExtractionBridge;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Recycles stable P14D mesh ids when chunks or client levels leave the immutable ray scene. */
@Mixin(value = SceneExtractionBridge.class, remap = false)
public abstract class SceneExtractionBridgeBlockEntityMixin {
    @Inject(method = "onChunkUnloaded", at = @At("HEAD"))
    private static void totemLumen$releaseBlockEntityMeshes(
            ClientLevel level,
            LevelChunk chunk,
            CallbackInfo ci
    ) {
        BlockEntityRenderGeometryCache.releaseChunk(
                level.dimension().identifier().toString(),
                chunk.getPos().x(),
                chunk.getPos().z()
        );
    }

    @Inject(method = "onLevelChanged", at = @At("HEAD"))
    private static void totemLumen$clearBlockEntityMeshes(ClientLevel level, CallbackInfo ci) {
        BlockEntityRenderGeometryCache.clear();
    }
}
