package dev.totem.lumen;

import dev.totem.lumen.config.ServerLightingConfigStore;
import dev.totem.lumen.config.ServerLightingCommands;
import dev.totem.lumen.gameplay.light.DimensionLightingRulesReloadListener;
import dev.totem.lumen.gameplay.light.ServerGameplayLightingManager;
import dev.totem.lumen.gameplay.light.ServerHeldLightManager;
import dev.totem.lumen.gameplay.light.ServerLightingViewService;
import dev.totem.lumen.gameplay.light.SpawnLightProfilesReloadListener;
import dev.totem.lumen.network.LightingWorldRulesPayload;
import dev.totem.lumen.network.HeldLightsPayload;
import dev.totem.lumen.network.ServerLightingViewPackets;
import dev.totem.lumen.world.LightingWorldRulesReloadListener;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import dev.totem.lumen.world.EffectiveLightingRules;
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
        boolean registered = ResourceLoader.registerBuiltinPack(
                net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "default_lighting"),
                FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow(),
                Component.translatable("pack.totem-lumen.default_lighting"),
                PackActivationType.DEFAULT_ENABLED);
        if (!registered) throw new IllegalStateException("Totem Lumen default lighting data pack is missing");
        boolean testPackRegistered = ResourceLoader.registerBuiltinPack(
                net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "double_light_test"),
                FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow(),
                Component.translatable("pack.totem-lumen.double_light_test"),
                PackActivationType.NORMAL);
        if (!testPackRegistered) throw new IllegalStateException("Totem Lumen double-light test data pack is missing");
        PayloadTypeRegistry.clientboundPlay().register(
                LightingWorldRulesPayload.TYPE,
                LightingWorldRulesPayload.CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(HeldLightsPayload.TYPE, HeldLightsPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
                ServerLightingViewPackets.Summary.TYPE, ServerLightingViewPackets.Summary.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
                ServerLightingViewPackets.Page.TYPE, ServerLightingViewPackets.Page.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                ServerLightingViewPackets.PageRequest.TYPE, ServerLightingViewPackets.PageRequest.CODEC);
        ServerLightingViewService.register();

        ResourceLoader serverData = ResourceLoader.get(PackType.SERVER_DATA);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> ServerLightingConfigStore.loadInitial());
        ServerLightingCommands.register();
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
        ServerTickEvents.END_SERVER_TICK.register(ServerHeldLightManager::tick);

        ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> {
            if (ServerPlayNetworking.canSend(listener, LightingWorldRulesPayload.TYPE)) {
                sender.sendPacket(new LightingWorldRulesPayload(
                        EffectiveLightingRules.current().rules(), LightingWorldRulesReloadListener.currentTuning()
                ));
            }
            ServerLightingViewService.sendSummary(listener.getPlayer());
        });

        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            if (!success) {
                return;
            }

            LightingWorldRulesPayload payload = new LightingWorldRulesPayload(
                    EffectiveLightingRules.current().rules(), LightingWorldRulesReloadListener.currentTuning()
            );
            int recipients = 0;
            for (var player : server.getPlayerList().getPlayers()) {
                if (ServerPlayNetworking.canSend(player, LightingWorldRulesPayload.TYPE)) {
                    ServerPlayNetworking.send(player, payload);
                    recipients++;
                }
            }

            ServerGameplayLightingManager.refreshWorldRules(server);
            ServerHeldLightManager.forceResync();
            ServerLightingViewService.broadcast(server);
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
            ServerHeldLightManager.clear();
            ServerLightingViewService.clear();
            ServerLightingConfigStore.reset();
            EffectiveLightingRules.reset();
            LightingWorldRulesReloadListener.reset();
            SpawnLightProfilesReloadListener.reset();
            DimensionLightingRulesReloadListener.reset();
        });
        LOGGER.info("Totem Lumen server-authoritative gameplay lighting registered");
    }

    public static void broadcastLightingRules(net.minecraft.server.MinecraftServer server) {
        LightingWorldRulesPayload payload = new LightingWorldRulesPayload(
                EffectiveLightingRules.current().rules(), LightingWorldRulesReloadListener.currentTuning());
        for (var player : server.getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(player, LightingWorldRulesPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }
}
