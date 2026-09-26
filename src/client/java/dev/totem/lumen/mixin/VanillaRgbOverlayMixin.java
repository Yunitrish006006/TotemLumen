package dev.totem.lumen.mixin;

import dev.totem.lumen.integration.HeldLightPostRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Vector4f;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class VanillaRgbOverlayMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void totemLumen$drawHeldLight(GraphicsResourceAllocator allocator,
                                            boolean renderBlockOutline, net.minecraft.client.renderer.state.level.CameraRenderState camera,
                                            GpuBufferSlice fog, Vector4f fogColor,
                                            boolean renderSky, boolean renderWeather, CallbackInfo ci) {
        // RGB terrain is already lit by its vertex colors. The retired volume overlay draws
        // visible cube boundaries around partial blocks, so this hook only adds held light.
        HeldLightPostRenderer.render(camera);
    }
}
