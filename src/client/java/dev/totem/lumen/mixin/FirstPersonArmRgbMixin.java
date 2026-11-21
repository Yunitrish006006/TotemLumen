package dev.totem.lumen.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.totem.lumen.integration.EntityRgbLighting;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonArmRgbMixin {
    @WrapMethod(method = "renderPlayerHand")
    private void totemLumen$lightArm(PoseStack poses, SubmitNodeCollector collector, int light,
            HumanoidArm arm, PlayerRenderState player, Operation<Void> original) {
        var previous = EntityRgbLighting.current();
        var state = (EntityRgbLighting.State) player.avatarRenderState;
        var lighting = state == null ? null : state.totemLumen$getLighting();
        if (lighting == previous) {
            original.call(poses, collector, light, arm, player);
            return;
        }
        EntityRgbLighting.select(lighting);
        try {
            original.call(poses, collector, light, arm, player);
        } finally {
            EntityRgbLighting.select(previous);
        }
    }
}
