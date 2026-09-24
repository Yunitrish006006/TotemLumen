package dev.totem.lumen.mixin;

import dev.totem.lumen.integration.VanillaRgbOverlayRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class VanillaRgbOverlayMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void totemLumen$drawRgbOverlay(GraphicsResourceAllocator allocator, DeltaTracker delta,
                                            boolean renderBlockOutline, net.minecraft.client.renderer.state.level.CameraRenderState camera,
                                            Matrix4fc modelView, GpuBufferSlice fog, Vector4f fogColor,
                                            boolean renderSky, CallbackInfo ci) {
        VanillaRgbOverlayRenderer.render(modelView);
    }
}
