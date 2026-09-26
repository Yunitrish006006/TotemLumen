package dev.totem.lumen.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.totem.lumen.vulkan.P5StableLookupRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces Minecraft's level drawing phase after Totem Lumen has a complete frame.
 *
 * <p>Minecraft 26.3 separates level extraction from level drawing. Cancelling this method therefore
 * removes terrain/entities/sky/weather/particles from the vanilla GPU path without stopping the
 * extraction callbacks Totem Lumen uses to build its own immutable scene.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererTakeoverMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void totemLumen$replaceVanillaWorldDrawing(
            GraphicsResourceAllocator resourceAllocator,
            boolean renderOutline,
            CameraRenderState cameraState,
            GpuBufferSlice terrainFog,
            Vector4f fogColor,
            boolean shouldRenderSky,
            boolean shouldRenderWeather,
            CallbackInfo ci
    ) {
        if (!P5StableLookupRenderer.readyForWorldTakeover()) {
            return;
        }

        P5StableLookupRenderer.presentToWorldTarget();
        ci.cancel();
    }
}
