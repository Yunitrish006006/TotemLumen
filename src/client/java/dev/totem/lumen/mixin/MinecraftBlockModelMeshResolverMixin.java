package dev.totem.lumen.mixin;

import dev.totem.lumen.integration.BlockEntityRenderGeometryCache;
import dev.totem.lumen.integration.MinecraftBlockModelMeshResolver;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds captured block-entity model quads after the ordinary P14C block geometry is resolved. */
@Mixin(value = MinecraftBlockModelMeshResolver.class, remap = false)
public abstract class MinecraftBlockModelMeshResolverMixin {
    @Inject(method = "geometryCode", at = @At("RETURN"), cancellable = true)
    private static void totemLumen$appendBlockEntityGeometry(
            BlockState state,
            ClientLevel level,
            BlockPos pos,
            CallbackInfoReturnable<Integer> cir
    ) {
        Integer composed = BlockEntityRenderGeometryCache.resolveGeometryCode(
                level.dimension().identifier().toString(),
                pos,
                state,
                level,
                cir.getReturnValue()
        );
        if (composed != null) cir.setReturnValue(composed);
    }
}
