package dev.totem.lumen.mixin;

import dev.totem.lumen.integration.ClientGameplayLightPredictor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderRegionCache.class)
abstract class RgbEmitterMeshInputMixin {
    @Inject(method = "createRegion", at = @At("HEAD"))
    private void totemLumen$primeNearbyEmitters(ClientLevel level, long node, CallbackInfoReturnable<RenderSectionRegion> ci) {
        ClientGameplayLightPredictor.previewEmitterSection(level, SectionPos.x(node), SectionPos.y(node), SectionPos.z(node));
    }
}
