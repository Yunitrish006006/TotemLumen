package dev.totem.lumen;

import dev.totem.lumen.gameplay.light.DimensionLightingRulesReloadListener;
import dev.totem.lumen.gameplay.light.ServerGameplayLightingManager;
import dev.totem.lumen.gameplay.light.SpawnLightProfilesReloadListener;
import dev.totem.lumen.network.LightingWorldRulesPayload;
import dev.totem.lumen.world.LightingWorldRulesReloadListener;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.server.packs.PackType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common/server entry point. This class never initializes or references Vulkan rendering code. */
public final class TotemLumen implements ModInitializer {
    public static final String MOD_ID = "totem-lumen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundPlay().register(
                LightingWorldRulesPayload.TYPE,
                LightingWorldRulesPayload.CODEC
        );

        ResourceLoader serverData = ResourceLoader.get(PackType.SERVER_DATA);
        serverData.registerReloadListener(
                LightingWorldRulesReloadListener.ID,
                new LightingWorldRulesReloadListener()
        );
        serverData.registerReloadListener(
                SpawnLightProfilesReloadListener.ID,
                new SpawnLightProfilesReloadListener()
        );
        serverData.registerReloadListener(
                DimensionLightingRulesReloadListener.ID,
                new DimensionLightingRulesReloadListener()
        );

        ServerLevelEvents.LOAD.register((server, level) ->
                ServerGameplayLightingManager.onLevelLoaded(level)
        );
        ServerLevelEvents.UNLOAD.register((server, level) ->
                ServerGameplayLightingManager.onLevelUnloaded(level)
        );
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) ->
                ServerGameplayLightingManager.onChunkLoaded(level, chunk)
        );
        ServerChunkEvents.CHUNK_UNLOAD.register(ServerGameplayLightingManager::onChunkUnloaded);
        ServerTickEvents.END_SERVER_TICK.register(ServerGameplayLightingManager::tick);

        ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> {
            if (ServerPlayNetworking.canSend(listener, LightingWorldRulesPayload.TYPE)) {
                sender.sendPacket(new LightingWorldRulesPayload(
                        LightingWorldRulesReloadListener.currentRules()
                ));
            }
        });

        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            if (!success) {
                return;
            }

            LightingWorldRulesPayload payload = new LightingWorldRulesPayload(
                    LightingWorldRulesReloadListener.currentRules()
            );
            int recipients = 0;
            for (var player : server.getPlayerList().getPlayers()) {
                if (ServerPlayNetworking.canSend(player, LightingWorldRulesPayload.TYPE)) {
                    ServerPlayNetworking.send(player, payload);
                    recipients++;
                }
            }

            ServerGameplayLightingManager.refreshWorldRules(server);
            LOGGER.info(
                    "Synchronized {} lighting world rule(s) to {} Totem Lumen client(s) after data-pack reload; "
                            + "server profiles: spawn={}, dimensions={}",
                    payload.rules().size(),
                    recipients,
                    SpawnLightProfilesReloadListener.currentProfiles().size(),
                    DimensionLightingRulesReloadListener.currentRules().size()
            );
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ServerGameplayLightingManager.clearAll();
            LightingWorldRulesReloadListener.reset();
            SpawnLightProfilesReloadListener.reset();
            DimensionLightingRulesReloadListener.reset();
        });
        LOGGER.info("Totem Lumen server-authoritative gameplay lighting registered");
    }
}
