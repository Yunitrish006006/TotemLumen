package dev.totem.lumen;

import dev.totem.lumen.render.RendererBootstrap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TotemLumenClient implements ClientModInitializer {
    public static final String MOD_ID = "totem-lumen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Totem Lumen");
        RendererBootstrap.initialize();
        ClientTickEvents.END_CLIENT_TICK.register(client -> RendererBootstrap.tick());
    }
}
