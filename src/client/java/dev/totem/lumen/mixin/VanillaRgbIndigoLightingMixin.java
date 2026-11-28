package dev.totem.lumen.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.totem.lumen.integration.LumiaTerrainMaterials;
import dev.totem.lumen.integration.LumiaPipelines;
import dev.totem.lumen.integration.VanillaRgbLighting;
import dev.totem.lumen.render.RendererSettings;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hooks Fabric Indigo's actual terrain quad transform path used by the development client. */
@Mixin(targets = "net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl", remap = false)
public abstract class VanillaRgbIndigoLightingMixin {
    @Shadow private BlockAndTintGetter level;
    @Shadow private BlockPos pos;
    @Shadow private BlockState blockState;

    // Indigo shades authored vertex colours in place before multiplying the biome tint.
    @ModifyArg(method = "shadeQuad", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/ARGB;scaleRGB(IF)I"), index = 1, require = 2)
    private float totemLumen$ambientFactor(float shade) {
        return RendererSettings.lumiaEnabled() ? 1.0f : shade;
    }

    @ModifyExpressionValue(method = "shadeQuad", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;cardinalLighting()Lnet/minecraft/world/level/CardinalLighting;"))
    private CardinalLighting totemLumen$directionalFactor(CardinalLighting light) {
        return RendererSettings.lumiaEnabled() ? LumiaPipelines.NEUTRAL_CARDINAL : light;
    }

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
        if (RendererSettings.lumiaEnabled() && level instanceof RenderSectionRegion) {
            LumiaTerrainMaterials.tagTerrain(quad, blockState);
        }
        Direction face = quad.cullFace() != null ? quad.cullFace() : quad.nominalFace();
        VanillaRgbLighting.applySurfaceTint(quad, level, blockState, pos, face);
    }
}
