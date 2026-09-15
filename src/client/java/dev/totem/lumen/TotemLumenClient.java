package dev.totem.lumen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.totem.lumen.integration.P13EnvironmentCapture;
import dev.totem.lumen.integration.SceneExtractionBridge;
import dev.totem.lumen.render.RendererBootstrap;
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

    private static KeyMapping cycleDebugMode;
    private static boolean rendererRuntimeFailed;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Totem Lumen");

        KeyMapping.Category debugCategory = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "debug")
        );
        cycleDebugMode = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.totem-lumen.cycle_debug_mode",
                        InputConstants.Type.KEYSYM,
                        InputConstants.KEY_F8,
                        debugCategory
                )
        );

        RendererBootstrap.initialize();
        // Register the environment capture first so its END_EXTRACTION callback runs before the
        // scene bridge constructs the immutable FrameSnapshot for the same frame.
        P13EnvironmentCapture.initialize();
        SceneExtractionBridge.initialize();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            RendererBootstrap.tick();
            SceneExtractionBridge.tick();
            P5StableLookupRenderer.tickLifecycle(client);

            while (cycleDebugMode.consumeClick()) {
                P5StableLookupRenderer.DebugMode mode = P5StableLookupRenderer.cycleMode();
                if (client.player != null) {
                    client.player.sendSystemMessage(Component.literal("Totem Lumen debug: " + mode.label()));
                }
                LOGGER.info("P5 stable lookup debug mode changed to {}", mode.label());
            }
        });

        LevelRenderEvents.START_MAIN.register(context -> {
            // P4/P5 smoke tests used to run here during normal world entry. They duplicated shader
            // compilation, scene packing, GPU allocations and readback on the render thread. Those
            // correctness gates belong in CI/developer validation, not the player hot path.
            P5WorldDebugComposite.runOnceOnRenderThread();

            if (!rendererRuntimeFailed) {
                try {
                    P5StableLookupRenderer.runOnRenderThread();
                } catch (Throwable failure) {
                    // A renderer bootstrap failure must never prevent the player from entering the
                    // world. Disable Totem Lumen rendering for this client session and leave vanilla
                    // rendering usable while preserving the failure in the log for diagnosis.
                    rendererRuntimeFailed = true;
                    LOGGER.error(
                            "Totem Lumen persistent renderer failed during world entry; disabling it for this session so Minecraft can continue",
                            failure
                    );
                }
            }
        });

        // The ray-traced world composite is still temporarily presented through the HUD pipeline.
        // Register it first so vanilla HUD elements (hotbar, health, crosshair, chat, etc.) render
        // afterwards and remain visible. A later renderer milestone will move this composite out of
        // the HUD pipeline entirely and into the world/composite stage.
        HudElementRegistry.addFirst(
                Identifier.fromNamespaceAndPath(MOD_ID, "p5_debug_overlay"),
                (graphics, deltaTracker) -> P5StableLookupRenderer.drawHud(graphics)
        );

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> P5StableLookupRenderer.shutdown());
    }
}
