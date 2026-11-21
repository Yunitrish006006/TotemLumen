package dev.totem.lumen.mixin;

import dev.totem.lumen.integration.EntityRgbLighting;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public abstract class EntityRgbStateMixin implements EntityRgbLighting.State {
    @Unique private EntityRgbLighting totemLumen$lighting;

    public EntityRgbLighting totemLumen$getLighting() { return totemLumen$lighting; }
    public void totemLumen$setLighting(EntityRgbLighting lighting) { totemLumen$lighting = lighting; }
}
