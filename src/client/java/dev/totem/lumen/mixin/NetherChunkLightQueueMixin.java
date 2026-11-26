package dev.totem.lumen.mixin;

import dev.totem.lumen.render.RendererSettings;
import java.util.Deque;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
abstract class NetherChunkLightQueueMixin {
    @Shadow @Final private Deque<Runnable> lightUpdateQueue;
    @Unique private long totemLumen$lastLightTick = Long.MIN_VALUE;

    // The first mesh waits for vanilla light in all eight neighboring columns.
    // Preserve packet ordering and only spend extra time while received neighbors are waiting.
    @Inject(method = "pollLightUpdates", at = @At("RETURN"))
    private void totemLumen$finishNearbyChunkLight(CallbackInfo ci) {
        ClientLevel level = (ClientLevel)(Object)this;
        if (lightUpdateQueue.isEmpty() || level.dimension() != Level.NETHER
                || RendererSettings.renderProfile() != RendererSettings.RenderProfile.MINECRAFT_RGB
                || totemLumen$lastLightTick == level.getGameTime()) return;
        var player = Minecraft.getInstance().player;
        if (player == null || player.level() != level) return;
        totemLumen$lastLightTick = level.getGameTime();
        var center = player.chunkPosition();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = center.x() + dx, z = center.z() + dz;
                if (!level.hasChunk(x, z) || level.getLightEngine().lightOnInColumn(SectionPos.asLong(x, 0, z))) continue;
                long end = System.nanoTime() + 400_000L;
                for (int i = 0; i < 8 && System.nanoTime() < end; i++) {
                    var next = lightUpdateQueue.pollFirst();
                    if (next == null) break;
                    next.run();
                }
                return;
            }
        }
    }
}
