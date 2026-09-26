package dev.totem.lumen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.totem.lumen.gui.TotemLumenVideoSettingsIntegration;
import dev.totem.lumen.gui.ClientServerLightingViewState;
import dev.totem.lumen.integration.ClientLightingWorldRules;
import dev.totem.lumen.integration.ClientGameplayLightField;
import dev.totem.lumen.integration.ClientGameplayLightPredictor;
import dev.totem.lumen.integration.ClientHeldLightState;
import dev.totem.lumen.integration.EntityRenderGeometryCache;
import dev.totem.lumen.integration.FluidRenderGeometryCache;
import dev.totem.lumen.integration.LabPbrTextureRegistry;
import dev.totem.lumen.integration.MinecraftBlockModelMeshResolver;
import dev.totem.lumen.integration.P13EnvironmentCapture;
import dev.totem.lumen.integration.SceneExtractionBridge;
import dev.totem.lumen.integration.VanillaRgbLighting;
import dev.totem.lumen.network.LightingWorldRulesPayload;
import dev.totem.lumen.network.HeldLightsPayload;
import dev.totem.lumen.network.ServerLightingViewPackets;
import dev.totem.lumen.render.RendererBootstrap;
import dev.totem.lumen.render.RendererCompileProgressNotifier;
import dev.totem.lumen.render.RendererSettings;
import dev.totem.lumen.vulkan.P5StableLookupRenderer;
import dev.totem.lumen.vulkan.P5WorldDebugComposite;
import dev.totem.lumen.vulkan.VulkanComputeProgram;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TotemLumenClient implements ClientModInitializer {
    public static final String MOD_ID = "totem-lumen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyMapping cycleDebugMode;
    private static KeyMapping cyclePerformanceProbe;
    private static boolean rendererRuntimeFailed;
    private static RendererSettings.RenderProfile lastRenderProfile;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Totem Lumen");
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            // The development client otherwise pauses the integrated server as soon as the
            // window loses focus, which can freeze RGB propagation while the client is being
            // launched or inspected. Keep release clients on vanilla behaviour.
            if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
                client.options.pauseOnLostFocus = false;
                LOGGER.info("Development client: pause-on-lost-focus disabled for RGB propagation testing");
            }
        });
        RendererSettings.initialize();
        lastRenderProfile = RendererSettings.renderProfile();

        ClientPlayNetworking.registerGlobalReceiver(LightingWorldRulesPayload.TYPE, (payload, context) -> {
            if (ClientLightingWorldRules.apply(payload.rules(), payload.tuning())) {
                SceneExtractionBridge.refreshLightingWorldRules();
                ClientGameplayLightPredictor.requestRebuild();
            }
            LOGGER.info(
                    "Applied {} server-authoritative lighting world rule(s)",
                    payload.rules().size()
            );
        });
        ClientPlayNetworking.registerGlobalReceiver(HeldLightsPayload.TYPE, (payload, context) ->
                ClientHeldLightState.apply(payload)
        );
        ClientPlayNetworking.registerGlobalReceiver(ServerLightingViewPackets.Summary.TYPE,
                (payload, context) -> ClientServerLightingViewState.apply(payload));
        ClientPlayNetworking.registerGlobalReceiver(ServerLightingViewPackets.Page.TYPE,
                (payload, context) -> ClientServerLightingViewState.apply(payload));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.level != null) {
                String dimensionId = client.level.dimension().identifier().toString();
                ClientGameplayLightField.setActiveDimension(dimensionId);
                FluidRenderGeometryCache.setActiveDimension(dimensionId);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
            if (ClientLightingWorldRules.reset()) {
                LOGGER.info("Cleared server-authoritative lighting world rules after disconnect");
            }
            ClientGameplayLightField.clear();
            ClientHeldLightState.clear();
            ClientServerLightingViewState.clear();
            ClientGameplayLightPredictor.clear();
            FluidRenderGeometryCache.clear();
            EntityRenderGeometryCache.clear();
            RendererCompileProgressNotifier.reset();
        });

        // Only the full Totem profile owns the monolithic Vulkan shader. MINECRAFT_RGB keeps
        // Minecraft's normal world renderer and uses its independent RGB overlay path.
        if (RendererSettings.rendererEnabled()) {
            // GLSL -> SPIR-V starts before the Vulkan device exists. Once RendererBootstrap sees
            // the device it separately starts driver/MoltenVK pipeline compilation on another
            // worker.
            VulkanComputeProgram.prewarmMainGiShader();
        }

        KeyMapping.Category debugCategory = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "debug")
        );
        cycleDebugMode = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.totem-lumen.cycle_debug_mode",
                        InputConstants.Type.KEYBOARD,
                        InputConstants.KEY_F8,
                        debugCategory
                )
        );
        cyclePerformanceProbe = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.totem-lumen.cycle_performance_probe",
                        InputConstants.Type.KEYBOARD,
                        InputConstants.KEY_F9,
                        debugCategory
                )
        );

        TotemLumenVideoSettingsIntegration.initialize();
        RendererBootstrap.initialize();
        // Register the environment capture first so its END_EXTRACTION callback runs before the
        // scene bridge constructs the immutable FrameSnapshot for the same frame.
        P13EnvironmentCapture.initialize();
        SceneExtractionBridge.initialize();
        ClientChunkEvents.CHUNK_LOAD.register(ClientGameplayLightPredictor::onChunkLoaded);
        ClientChunkEvents.CHUNK_UNLOAD.register(ClientGameplayLightPredictor::onChunkUnloaded);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            RendererBootstrap.tick();
            RendererSettings.RenderProfile renderProfile = RendererSettings.renderProfile();
            if (renderProfile != lastRenderProfile) {
                if (client.level != null) {
                    ClientGameplayLightPredictor.onProfileChanged(client.level, client.player);
                }
                if (RendererSettings.rendererEnabled()) {
                    // Allow switching from the independent Minecraft profiles into Totem
                    // without requiring a second client initialization callback.
                    VulkanComputeProgram.prewarmMainGiShader();
                }
                lastRenderProfile = renderProfile;
            }
            if (client.level != null) {
                String dimensionId = client.level.dimension().identifier().toString();
                ClientGameplayLightField.setActiveDimension(dimensionId);
                FluidRenderGeometryCache.setActiveDimension(dimensionId);
                if (renderProfile == RendererSettings.RenderProfile.MINECRAFT_RGB) {
                    VanillaRgbLighting.updateSkyPalette(client.level);
                }
                ClientGameplayLightPredictor.tick(client.level, client.player);
                for (ClientGameplayLightField.SectionCoordinate section
                        : ClientGameplayLightField.drainDirtySections(dimensionId, 32)) {
                    // Re-extract only sections touched by RGB propagation. This updates the
                    // vanilla surface tint without invalidating the entire compiled world.
                    client.level.setSectionRangeDirty(
                            section.x(), section.y(), section.z(),
                            section.x(), section.y(), section.z()
                    );
                }
                if (!RendererSettings.rendererEnabled() || !RendererSettings.entityRayTracingEnabled()) {
                    EntityRenderGeometryCache.clear();
                }
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
            while (cyclePerformanceProbe.consumeClick()) {
                P5StableLookupRenderer.DebugMode mode = P5StableLookupRenderer.cyclePerformanceProbeMode();
                if (client.player != null) {
                    client.player.sendSystemMessage(Component.literal(
                            "Totem Lumen perf probe: " + mode.label()
                                    + " — keep camera still until the next result is logged"
                    ));
                }
                LOGGER.info("Totem Lumen performance probe mode changed to {}", mode.label());
            }
        });

        // Submit Totem compute after the immutable frame snapshot has been extracted. This must
        // stay outside LevelRenderer.render(): once world takeover is active that vanilla drawing
        // method is cancelled entirely, while extraction continues every frame.
        LevelExtractionEvents.END_EXTRACTION.register(context -> {
            var client = net.minecraft.client.Minecraft.getInstance();
            ClientHeldLightState.capture(client,
                    client.getDeltaTracker().getGameTimeDeltaPartialTick(true));
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

        // World presentation is no longer a HUD overlay. LevelRendererTakeoverMixin presents the
        // latest completed Totem frame into the main world target and skips vanilla level drawing.
        // HUD elements remain a separate later phase, so F1 affects only the HUD.
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(MOD_ID, "compile_progress"),
                (graphics, deltaTracker) -> RendererCompileProgressNotifier.drawHud(graphics)
        );

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            RendererCompileProgressNotifier.reset();
            ClientGameplayLightPredictor.clear();
            FluidRenderGeometryCache.clear();
            EntityRenderGeometryCache.clear();
            P5StableLookupRenderer.shutdown();
            VulkanComputeProgram.shutdownPipelineCaches();
        });
    }
}
