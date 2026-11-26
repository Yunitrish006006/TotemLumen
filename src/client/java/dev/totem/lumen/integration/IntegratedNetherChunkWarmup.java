package dev.totem.lumen.integration;

import dev.totem.lumen.render.RendererSettings;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/** Give the host player's destination a short loading-only head start in the normal chunk pipeline. */
public final class IntegratedNetherChunkWarmup {
    private ServerLevel level;
    private ChunkPos previous, target;
    private int remaining;

    public static void register() {
        var warmup = new IntegratedNetherChunkWarmup();
        ServerTickEvents.END_SERVER_TICK.register(warmup::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> warmup.reset());
    }

    private void reset() {
        level = null;
        previous = null;
        target = null;
        remaining = 0;
    }

    private void tick(MinecraftServer server) {
        if (!server.isSingleplayer() || server.getSingleplayerProfile() == null
                || RendererSettings.renderProfile() != RendererSettings.RenderProfile.MINECRAFT_RGB) {
            reset();
            return;
        }
        var player = server.getPlayerList().getPlayer(server.getSingleplayerProfile().id());
        if (player == null || player.level().dimension() != Level.NETHER) {
            reset();
            return;
        }
        var here = player.chunkPosition();
        if (level != player.level() || !here.equals(previous)) {
            boolean arrival = level != player.level() || previous == null
                    || Math.abs(here.x() - previous.x()) > 1 || Math.abs(here.z() - previous.z()) > 1;
            level = player.level();
            target = arrival ? here : new ChunkPos(here.x() + Integer.signum(here.x() - previous.x()) * 2,
                    here.z() + Integer.signum(here.z() - previous.z()) * 2);
            previous = here;
            remaining = 20;
        }
        if (remaining <= 0) return;
        var cache = level.getChunkSource();
        if (cache.getChunkNow(target.x(), target.z()) != null) {
            remaining = 0;
            return;
        }
        remaining--;
        // UNKNOWN expires after one tick and has no simulation or persistence flag.
        // Adding it only schedules work. Never synchronously fetch or generate a chunk.
        cache.addTicketWithRadius(TicketType.UNKNOWN, target, 1);
    }
}
