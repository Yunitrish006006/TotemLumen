package dev.totem.lumen.mixin;

import com.mojang.blaze3d.vertex.QuadInstance;
import dev.totem.lumen.integration.VanillaRgbLighting;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds server-rule RGB to emissive vanilla block surfaces without replacing vanilla geometry. */
@Mixin(ModelBlockRenderer.class)
public abstract class VanillaRgbLightingMixin {
    @org.spongepowered.asm.mixin.Unique
    private net.minecraft.client.renderer.block.BlockAndTintGetter totemLumen$world;
    @org.spongepowered.asm.mixin.Unique
    private BlockState totemLumen$state;
    @org.spongepowered.asm.mixin.Unique
    private BlockPos totemLumen$pos;
    @org.spongepowered.asm.mixin.Unique
    private Direction totemLumen$direction;

    @Inject(method = "putQuadWithTint", at = @At("HEAD"))
    private void totemLumen$captureQuadContext(
            BlockQuadOutput output,
            float x,
            float y,
            float z,
            net.minecraft.client.renderer.block.BlockAndTintGetter world,
            BlockState state,
            BlockPos pos,
            BakedQuad quad,
            CallbackInfo ci
    ) {
        totemLumen$world = world;
        totemLumen$state = state;
        totemLumen$pos = pos;
        totemLumen$direction = quad.direction();
    }

    @ModifyArg(
            method = "putQuadWithTint",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/BlockQuadOutput;put(FFFLnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"
            ),
            index = 4
    )
    private QuadInstance totemLumen$applyRgbSurfaceColor(QuadInstance instance) {
        if (totemLumen$world != null && totemLumen$state != null && totemLumen$pos != null) {
            VanillaRgbLighting.applySurfaceTint(
                    instance,
                    totemLumen$world,
                    totemLumen$state,
                    totemLumen$pos,
                    totemLumen$direction
            );
        }
        return instance;
    }
}
