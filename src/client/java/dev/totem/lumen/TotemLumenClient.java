package dev.totem.lumen;

import dev.totem.lumen.integration.SceneExtractionBridge;
import dev.totem.lumen.render.RendererBootstrap;
import dev.totem.lumen.vulkan.P4ComputeSmokeTest;
import dev.totem.lumen.vulkan.P4DdaSmokeTest;
import dev.totem.lumen.vulkan.P5DebugCompositeTest;
import dev.totem.lumen.vulkan.P5DebugRayGridTest;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TotemLumenClient implements ClientModInitializer {
    public static final String MOD_ID = "totem-lumen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Totem Lumen");
        RendererBootstrap.initialize();
        SceneExtractionBridge.initialize();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            RendererBootstrap.tick();
            SceneExtractionBridge.tick();
        });

        LevelRenderEvents.START_MAIN.register(context -> {
            P4ComputeSmokeTest.runOnceOnRenderThread();
            P4DdaSmokeTest.runOnceOnRenderThread();
            P5DebugRayGridTest.runOnceOnRenderThread();
            P5DebugCompositeTest.runOnceOnRenderThread();
        });

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(MOD_ID, "p5_debug_overlay"),
                (graphics, deltaTracker) -> P5DebugCompositeTest.drawHud(graphics)
        );
    }
}
