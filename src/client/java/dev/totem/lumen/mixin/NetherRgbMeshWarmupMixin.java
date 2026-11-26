package dev.totem.lumen.mixin;

import dev.totem.lumen.integration.NetherRgbMeshWarmup;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
abstract class NetherRgbMeshWarmupMixin {
    @Shadow private ClientLevel level;
    @Shadow private SectionUpdateTracker sectionUpdateTracker;
    @Shadow @Final private LevelRenderer levelRenderer;
    @Shadow @Final private LevelRenderState levelRenderState;

    @Inject(method = "extract", at = @At("TAIL"))
    private void totemLumen$prepareNearbyTerrain(CallbackInfo ci) {
        NetherRgbMeshWarmup.extract(level, sectionUpdateTracker, levelRenderer, levelRenderState);
    }
}
