package dev.totem.lumen.config;

import dev.totem.lumen.TotemLumen;
import dev.totem.lumen.gameplay.light.ServerGameplayLightingManager;
import dev.totem.lumen.gameplay.light.ServerHeldLightManager;
import dev.totem.lumen.gameplay.light.ServerLightingViewService;
import dev.totem.lumen.world.EffectiveLightingRules;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

/** Operator-only configuration reload; normal players use the separate read-only preview. */
public final class ServerLightingCommands {
    private ServerLightingCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("totemlumen")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                        .then(Commands.literal("config")
                                .then(Commands.literal("reload").executes(context -> {
                                    var source = context.getSource();
                                    try {
                                        ServerLightingConfig beforeConfig = ServerLightingConfigStore.current();
                                        var beforeRules = EffectiveLightingRules.current().rules().rules();
                                        boolean changed = ServerLightingConfigStore.reload();
                                        if (changed) {
                                            boolean rulesChanged = !beforeRules.equals(
                                                    EffectiveLightingRules.current().rules().rules());
                                            if (rulesChanged) {
                                                ServerGameplayLightingManager.refreshWorldRules(source.getServer());
                                                TotemLumen.broadcastLightingRules(source.getServer());
                                            }
                                            if (rulesChanged || beforeConfig.heldLightEnabled()
                                                    != ServerLightingConfigStore.current().heldLightEnabled()) {
                                                ServerHeldLightManager.forceResync();
                                            }
                                            ServerLightingViewService.broadcast(source.getServer());
                                        }
                                        source.sendSuccess(() -> Component.translatable(
                                                changed ? "message.totem-lumen.config.reloaded"
                                                        : "message.totem-lumen.config.unchanged"), true);
                                        return 1;
                                    } catch (Exception failure) {
                                        TotemLumen.LOGGER.warn("Server lighting config reload rejected", failure);
                                        source.sendFailure(Component.translatable(
                                                "message.totem-lumen.config.invalid", failure.getMessage()));
                                        return 0;
                                    }
                                })))));
    }
}
