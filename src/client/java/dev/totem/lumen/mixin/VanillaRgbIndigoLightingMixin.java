package dev.totem.lumen.mixin;

import dev.totem.lumen.integration.VanillaRgbLighting;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hooks Fabric Indigo's actual terrain quad transform path used by the development client. */
@Mixin(targets = "net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl", remap = false)
public abstract class VanillaRgbIndigoLightingMixin {
    @Shadow private BlockAndTintGetter level;
    @Shadow private BlockPos pos;
    @Shadow private BlockState blockState;

    // Indigo translates model-local vertices into section-local coordinates at the end of
    // transform(). RGB sampling needs model-local coordinates so it can add the world block
    // position exactly once; sampling at TAIL looked up the wrong light voxels.
    @Inject(
            method = "transform",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/fabricmc/fabric/api/client/renderer/v1/mesh/MutableQuadView;translate(FFF)Lnet/fabricmc/fabric/api/client/renderer/v1/mesh/MutableQuadView;",
                    shift = At.Shift.BEFORE
            ),
            require = 1
    )
    private void totemLumen$applyRgb(MutableQuadView quad, CallbackInfoReturnable<Boolean> cir) {
        Direction face = quad.cullFace() != null ? quad.cullFace() : quad.nominalFace();
        VanillaRgbLighting.applySurfaceTint(quad, level, blockState, pos, face);
    }
}
