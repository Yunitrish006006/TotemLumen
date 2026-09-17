package dev.totem.lumen.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.totem.lumen.integration.FluidRenderGeometryCapture;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures Minecraft 26.2's renderer-resolved fluid faces without reimplementing fluid heights. */
@Mixin(FluidRenderer.class)
public abstract class FluidRendererCaptureMixin {
    @Inject(method = "tesselate", at = @At("HEAD"))
    private void totemLumen$beginFluidCapture(
            BlockAndTintGetter level,
            BlockPos pos,
            FluidRenderer.Output output,
            BlockState blockState,
            FluidState fluidState,
            CallbackInfo ci
    ) {
        FluidRenderGeometryCapture.begin(pos, fluidState);
    }

    @Inject(method = "addFace", at = @At("HEAD"))
    private void totemLumen$captureFluidFace(
            VertexConsumer builder,
            float x0, float y0, float z0, float u0, float v0,
            float x1, float y1, float z1, float u1, float v1,
            float x2, float y2, float z2, float u2, float v2,
            float x3, float y3, float z3, float u3, float v3,
            int color,
            int lightCoords,
            boolean addBackFace,
            CallbackInfo ci
    ) {
        FluidRenderGeometryCapture.face(
                x0, y0, z0, u0, v0,
                x1, y1, z1, u1, v1,
                x2, y2, z2, u2, v2,
                x3, y3, z3, u3, v3,
                color,
                addBackFace
        );
    }

    @Inject(method = "tesselate", at = @At("RETURN"))
    private void totemLumen$endFluidCapture(
            BlockAndTintGetter level,
            BlockPos pos,
            FluidRenderer.Output output,
            BlockState blockState,
            FluidState fluidState,
            CallbackInfo ci
    ) {
        FluidRenderGeometryCapture.end();
    }
}
