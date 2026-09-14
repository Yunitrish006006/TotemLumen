package dev.totem.lumen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.totem.lumen.integration.SceneExtractionBridge;
import dev.totem.lumen.render.RendererBootstrap;
import dev.totem.lumen.vulkan.P4ComputeSmokeTest;
import dev.totem.lumen.vulkan.P4DdaSmokeTest;
import dev.totem.lumen.vulkan.P5DebugCompositeTest;
import dev.totem.lumen.vulkan.P5DebugRayGridTest;
import dev.totem.lumen.vulkan.P5StableLookupRenderer;
import dev.totem.lumen.vulkan.P5WorldDebugComposite;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TotemLumenClient implements ClientModInitializer {
    public static final String MOD_ID = "totem-lumen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final KeyMapping.Category DEBUG_CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(MOD_ID, "debug")
    );
    private static final KeyMapping CYCLE_DEBUG_MODE = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                    "key.totem-lumen.cycle_debug_mode",
                    InputConstants.Type.KEYSYM,
                    InputConstants.KEY_F8,
                    DEBUG_CATEGORY
            )
    );

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Totem Lumen");
        RendererBootstrap.initialize();
        SceneExtractionBridge.initialize();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            RendererBootstrap.tick();
            SceneExtractionBridge.tick();
            P5StableLookupRenderer.tickLifecycle(client);

            while (CYCLE_DEBUG_MODE.consumeClick()) {
                P5StableLookupRenderer.DebugMode mode = P5StableLookupRenderer.cycleMode();
                if (client.player != null) {
                    client.player.sendSystemMessage(Component.literal("Totem Lumen debug: " + mode.label()));
                }
                LOGGER.info("P5 stable lookup debug mode changed to {}", mode.label());
            }
        });

        LevelRenderEvents.START_MAIN.register(context -> {
            P4ComputeSmokeTest.runOnceOnRenderThread();
            P4DdaSmokeTest.runOnceOnRenderThread();
            P5DebugRayGridTest.runOnceOnRenderThread();
            P5DebugCompositeTest.runOnceOnRenderThread();
            P5WorldDebugComposite.runOnceOnRenderThread();
            P5StableLookupRenderer.runOnRenderThread();
        });

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(MOD_ID, "p5_debug_overlay"),
                (graphics, deltaTracker) -> P5StableLookupRenderer.drawHud(graphics)
        );

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> P5StableLookupRenderer.shutdown());
    }
}
