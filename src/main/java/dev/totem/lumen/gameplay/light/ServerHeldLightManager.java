package dev.totem.lumen.gameplay.light;

import dev.totem.lumen.TotemLumen;
import dev.totem.lumen.config.ServerLightingConfigStore;
import dev.totem.lumen.network.HeldLightsPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Sends equipment changes, not player positions, using vanilla entity tracking for motion. */
public final class ServerHeldLightManager {
    private static final double TRACK_DISTANCE_SQUARED = 48.0 * 48.0;
    private static final Map<UUID, HeldLightsPayload> LAST_SENT = new HashMap<>();
    private static long ticks;
    private static int diagnosticChanges;

    private ServerHeldLightManager() {
    }

    public static void tick(MinecraftServer server) {
        ticks++;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (ServerPlayer recipient : players) {
            if (!ServerPlayNetworking.canSend(recipient, HeldLightsPayload.TYPE)) continue;
            List<ServerPlayer> nearby = ServerLightingConfigStore.current().heldLightEnabled() ? players.stream()
                    .filter(player -> player.level() == recipient.level())
                    .filter(player -> player.distanceToSqr(recipient) <= TRACK_DISTANCE_SQUARED)
                    .filter(player -> heldPacked(player) != 0)
                    .sorted(Comparator.comparingDouble(player -> player.distanceToSqr(recipient)))
                    .toList() : List.of();
            List<HeldLightsPayload.Source> sources = new ArrayList<>(HeldLightsPayload.MAX_SOURCES);
            for (ServerPlayer player : nearby) {
                appendSource(sources, player, InteractionHand.MAIN_HAND,
                        HeldLightSource.packedFor(player.getMainHandItem()));
                appendSource(sources, player, InteractionHand.OFF_HAND,
                        HeldLightSource.packedFor(player.getOffhandItem()));
                if (sources.size() == HeldLightsPayload.MAX_SOURCES) break;
            }
            sources.sort(Comparator.comparingInt(HeldLightsPayload.Source::entityId)
                    .thenComparing(HeldLightsPayload.Source::hand));
            HeldLightsPayload payload = new HeldLightsPayload(recipient.level().dimension().identifier(), sources);
            HeldLightsPayload previous = LAST_SENT.put(recipient.getUUID(), payload);
            if (!payload.equals(previous) || ticks % 40L == 0L) {
                ServerPlayNetworking.send(recipient, payload);
                if (!payload.equals(previous) && diagnosticChanges++ < 12) {
                    TotemLumen.LOGGER.info("Held light snapshot sent: recipientEntity={}, sources={}, dimension={}",
                            recipient.getId(), sources.size(), payload.dimension());
                }
            }
        }
        if (ticks % 200L == 0L) {
            LAST_SENT.keySet().retainAll(players.stream().map(ServerPlayer::getUUID).toList());
        }
    }

    public static void forceResync() {
        LAST_SENT.clear();
    }

    public static void clear() {
        LAST_SENT.clear();
        ticks = 0L;
        diagnosticChanges = 0;
    }

    private static char heldPacked(ServerPlayer player) {
        return PackedRgbLight.componentMax(
                HeldLightSource.packedFor(player.getMainHandItem()),
                HeldLightSource.packedFor(player.getOffhandItem())
        );
    }

    private static void appendSource(List<HeldLightsPayload.Source> sources, ServerPlayer player,
                                     InteractionHand hand, char packed) {
        if (packed != 0 && sources.size() < HeldLightsPayload.MAX_SOURCES) {
            sources.add(new HeldLightsPayload.Source(player.getId(), packed, hand));
        }
    }
}
