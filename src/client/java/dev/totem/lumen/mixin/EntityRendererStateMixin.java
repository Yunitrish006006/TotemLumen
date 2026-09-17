package dev.totem.lumen.mixin;

import dev.totem.lumen.integration.EntityRenderGeometryCache;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Binds Minecraft's per-frame render-state object to the stable world entity identity. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererStateMixin {
    private static final String CREATE_RENDER_STATE_26_2 =
            "createRenderState(Lnet/minecraft/world/entity/Entity;F)"
                    + "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;";

    @Inject(method = CREATE_RENDER_STATE_26_2, at = @At("RETURN"), require = 1)
    private <T extends Entity> void totemLumen$bindEntityRenderState(
            T entity,
            float partialTick,
            CallbackInfoReturnable<EntityRenderState> cir
    ) {
        EntityRenderGeometryCache.bind(entity, cir.getReturnValue());
    }
}
