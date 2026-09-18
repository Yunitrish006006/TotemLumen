package dev.totem.lumen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.totem.lumen.gui.TotemLumenVideoSettingsIntegration;
import dev.totem.lumen.integration.ClientLightingWorldRules;
import dev.totem.lumen.integration.EntityRenderGeometryCache;
import dev.totem.lumen.integration.FluidRenderGeometryCache;
import dev.totem.lumen.integration.LabPbrTextureRegistry;
import dev.totem.lumen.integration.MinecraftBlockModelMeshResolver;
import dev.totem.lumen.integration.P13EnvironmentCapture;
import dev.totem.lumen.integration.SceneExtractionBridge;
import dev.totem.lumen.network.LightingWorldRulesPayload;
import dev.totem.lumen.render.RendererBootstrap;
import dev.totem.lumen.render.RendererCompileProgressNotifier;
import dev.totem.lumen.render.RendererSettings;
import dev.totem.lumen.vulkan.P5StableLookupRenderer;
import dev.totem.lumen.vulkan.P5WorldDebugComposite;
import dev.totem.lumen.vulkan.VulkanComputeProgram;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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
        RendererSettings.initialize();

        ClientPlayNetworking.registerGlobalReceiver(LightingWorldRulesPayload.TYPE, (payload, context) -> {
            if (ClientLightingWorldRules.apply(payload.rules())) {
                SceneExtractionBridge.refreshLightingWorldRules();
            }
            LOGGER.info(
                    "Applied {} server-authoritative lighting world rule(s)",
                    payload.rules().size()
            );
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.level != null) {
                FluidRenderGeometryCache.setActiveDimension(
                        client.level.dimension().identifier().toString()
                );
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
            if (ClientLightingWorldRules.reset()) {
                LOGGER.info("Cleared server-authoritative lighting world rules after disconnect");
            }
            FluidRenderGeometryCache.clear();
            EntityRenderGeometryCache.clear();
            RendererCompileProgressNotifier.reset();
        });

        // GLSL -> SPIR-V starts before the Vulkan device exists. Once RendererBootstrap sees the
        // device it separately starts driver/MoltenVK pipeline compilation on another worker.
        VulkanComputeProgram.prewarmMainGiShader();

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

        TotemLumenVideoSettingsIntegration.initialize();
        RendererBootstrap.initialize();
        // Register the environment capture first so its END_EXTRACTION callback runs before the
        // scene bridge constructs the immutable FrameSnapshot for the same frame.
        P13EnvironmentCapture.initialize();
        SceneExtractionBridge.initialize();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            RendererBootstrap.tick();
            if (client.level != null) {
                String dimensionId = client.level.dimension().identifier().toString();
                FluidRenderGeometryCache.setActiveDimension(dimensionId);
                EntityRenderGeometryCache.prune(
                        dimensionId,
                        client.level.getGameTime()
                );
            }
            // P14C watches Minecraft's model-set identity independently of block updates so a
            // resource-pack reload schedules bounded section re-extraction even in a static world.
            MinecraftBlockModelMeshResolver.checkModelSetReload();
            LabPbrTextureRegistry.tick(client);
            SceneExtractionBridge.tick();
            P5StableLookupRenderer.tickLifecycle(client);
            RendererCompileProgressNotifier.tick(client);

            while (cycleDebugMode.consumeClick()) {
                P5StableLookupRenderer.DebugMode mode = P5StableLookupRenderer.cycleMode();
                if (client.player != null) {
                    client.player.sendSystemMessage(Component.literal("Totem Lumen debug: " + mode.label()));
                }
                LOGGER.info("P5 stable lookup debug mode changed to {}", mode.label());
            }
        });

        LevelRenderEvents.START_MAIN.register(context -> {
            // The old P5 world bootstrap is now a zero-GPU compatibility gate.
            P5WorldDebugComposite.runOnceOnRenderThread();

            if (rendererRuntimeFailed || !RendererBootstrap.readyForRendering()) {
                return;
            }

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
        });

        // The ray-traced world composite is still temporarily presented through the HUD pipeline.
        // Register it first so vanilla HUD elements (hotbar, health, crosshair, chat, etc.) render
        // afterwards and remain visible. A later renderer milestone will move this composite out of
        // the HUD pipeline entirely and into the world/composite stage.
        HudElementRegistry.addFirst(
                Identifier.fromNamespaceAndPath(MOD_ID, "p5_debug_overlay"),
                (graphics, deltaTracker) -> P5StableLookupRenderer.drawHud(graphics)
        );
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(MOD_ID, "compile_progress"),
                (graphics, deltaTracker) -> RendererCompileProgressNotifier.drawHud(graphics)
        );

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            RendererCompileProgressNotifier.reset();
            FluidRenderGeometryCache.clear();
            EntityRenderGeometryCache.clear();
            P5StableLookupRenderer.shutdown();
        });
    }
}
