package dev.totem.lumen.gui;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;

/** Adds Totem Lumen's settings entry point to Minecraft's Video Settings screen. */
public final class TotemLumenVideoSettingsIntegration {
    private static boolean initialized;

    private TotemLumenVideoSettingsIntegration() {
    }

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof VideoSettingsScreen)) return;

            int buttonWidth = 118;
            int x = Math.max(6, scaledWidth - buttonWidth - 6);
            Button button = Button.builder(
                    Component.translatable("screen.totem-lumen.entry"),
                    ignored -> client.gui.setScreen(new TotemLumenVideoSettingsScreen(screen))
            ).bounds(x, 6, buttonWidth, 20).build();

            Screens.getWidgets(screen).add(button);
        });
    }
}
