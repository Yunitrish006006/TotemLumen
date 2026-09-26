package dev.totem.lumen.mixin;

import com.mojang.blaze3d.vertex.QuadInstance;
import dev.totem.lumen.integration.VanillaRgbLighting;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies RGB after Minecraft 26.3 has prepared the final vanilla quad light data. */
@Mixin(BlockModelLighter.class)
public abstract class VanillaRgbLightingLighterMixin {
    @Inject(method = "prepareQuadAmbientOcclusion", at = @At("TAIL"))
    private void totemLumen$applyAmbientRgb(
            BlockAndTintGetter world,
            BlockState state,
            BlockPos pos,
            BakedQuad quad,
            QuadInstance instance,
            CallbackInfo ci
    ) {
        VanillaRgbLighting.applySurfaceTint(instance, world, state, pos, quad.direction());
    }

    @Inject(method = "prepareQuadFlat", at = @At("TAIL"))
    private void totemLumen$applyFlatRgb(
            BlockAndTintGetter world,
            BlockState state,
            BlockPos pos,
            int light,
            BakedQuad quad,
            QuadInstance instance,
            CallbackInfo ci
    ) {
        VanillaRgbLighting.applySurfaceTint(instance, world, state, pos, quad.direction());
    }
}
