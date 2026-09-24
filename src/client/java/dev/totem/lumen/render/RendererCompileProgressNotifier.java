package dev.totem.lumen.render;

import dev.totem.lumen.vulkan.P12FullBasePipeline;
import dev.totem.lumen.vulkan.P16MultipassReflection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Client-side system-message progress reporting for Totem Lumen's staged Vulkan pipeline startup.
 *
 * <p>MoltenVK exposes no useful percentage callback while vkCreateComputePipelines is compiling
 * Metal code, so this notifier deliberately reports real pipeline stages and elapsed time instead
 * of inventing a percentage. Messages are emitted only on stage transitions plus one heartbeat
 * every 30 seconds while a long-running compile remains in progress.</p>
 */
public final class RendererCompileProgressNotifier {
    private static final long HEARTBEAT_NANOS = 30_000_000_000L;

    private static Phase phase = Phase.NONE;
    private static long phaseStartedNanos;
    private static long lastHeartbeatNanos;

    private static boolean bootstrapReadyAnnounced;
    private static boolean fullReadyAnnounced;
    private static boolean p17ReadyAnnounced;
    private static boolean p16ReadyAnnounced;
    private static boolean fullFailureAnnounced;
    private static boolean p17FailureAnnounced;
    private static boolean p16FailureAnnounced;
    private static boolean finalSummaryAnnounced;
    private static RendererState terminalStateAnnounced;

    private RendererCompileProgressNotifier() {
    }

    public static void tick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return;
        }
        if (!RendererSettings.rendererEnabled()) {
            return;
        }

        long now = System.nanoTime();
        RendererState rendererState = RendererBootstrap.state();

        switch (rendererState) {
            case DISABLED_NON_VULKAN, DISABLED_UNSUPPORTED_VULKAN, PIPELINE_FAILED -> {
                announceTerminalState(client, rendererState);
                return;
            }
            case WAITING_FOR_DEVICE, WAITING_FOR_VULKAN_INTEROP -> {
                enterPhase(client, Phase.BACKEND, now, "message.totem-lumen.compile.backend");
                heartbeat(client, now, "message.totem-lumen.compile.backend_wait");
                return;
            }
            case WAITING_FOR_PIPELINE -> {
                enterPhase(client, Phase.BOOTSTRAP, now, "message.totem-lumen.compile.bootstrap");
                heartbeat(client, now, "message.totem-lumen.compile.bootstrap_wait");
                return;
            }
            case NEW -> {
                return;
            }
            case READY_FOR_SCENE_EXTRACTION -> {
                // Continue below: renderer bootstrap is usable and optional stages may still compile.
            }
        }

        if (!bootstrapReadyAnnounced) {
            bootstrapReadyAnnounced = true;
            send(client, "message.totem-lumen.compile.bootstrap_ready");
        }

        Throwable fullFailure = P12FullBasePipeline.failure();
        if (fullFailure != null) {
            if (!fullFailureAnnounced) {
                fullFailureAnnounced = true;
                send(client, "message.totem-lumen.compile.full_failed");
            }
            phase = Phase.DEGRADED;
            return;
        }

        if (!P12FullBasePipeline.ready()) {
            enterPhase(client, Phase.FULL_BASE, now, "message.totem-lumen.compile.full");
            heartbeat(
                    client,
                    now,
                    "message.totem-lumen.compile.full_wait",
                    P12FullBasePipeline.prewarmStage().label()
            );
            return;
        }

        if (!fullReadyAnnounced) {
            fullReadyAnnounced = true;
            send(client, "message.totem-lumen.compile.full_ready");
        }

        boolean p17Ready = true; // Dynamic entities are part of the full-lighting pipeline.
        boolean p16Ready = P16MultipassReflection.ready();
        Throwable p17Failure = null;
        Throwable p16Failure = P16MultipassReflection.failure();

        if (!p16Ready && p16Failure != null && !p16FailureAnnounced) {
            p16FailureAnnounced = true;
            send(client, "message.totem-lumen.compile.p16_failed");
        }

        if (!p17ReadyAnnounced) {
            p17ReadyAnnounced = true;
            send(client, "message.totem-lumen.compile.p17_ready");
        }
        if (p16Ready && !p16ReadyAnnounced) {
            p16ReadyAnnounced = true;
            send(client, "message.totem-lumen.compile.p16_ready");
        }

        boolean p17Finished = p17Ready || p17Failure != null;
        boolean p16Finished = p16Ready || p16Failure != null;
        if (!p17Finished || !p16Finished) {
            enterPhase(client, Phase.ADVANCED, now, "message.totem-lumen.compile.advanced");
            heartbeat(client, now, "message.totem-lumen.compile.advanced_wait");
            return;
        }

        if (!finalSummaryAnnounced) {
            finalSummaryAnnounced = true;
            phase = (p17Ready && p16Ready) ? Phase.COMPLETE : Phase.DEGRADED;
            send(
                    client,
                    p17Ready && p16Ready
                            ? "message.totem-lumen.compile.complete"
                            : "message.totem-lumen.compile.complete_degraded"
            );
        }
    }

    public static int progressPercent() {
        RendererState rendererState = RendererBootstrap.state();
        return switch (rendererState) {
            case NEW -> 0;
            case WAITING_FOR_DEVICE -> 5;
            case WAITING_FOR_VULKAN_INTEROP -> 10;
            case WAITING_FOR_PIPELINE -> 20;
            case DISABLED_NON_VULKAN, DISABLED_UNSUPPORTED_VULKAN, PIPELINE_FAILED -> 100;
            case READY_FOR_SCENE_EXTRACTION -> {
                Throwable fullFailure = P12FullBasePipeline.failure();
                if (fullFailure != null) {
                    yield 100;
                }
                if (!P12FullBasePipeline.ready()) {
                    yield 30;
                }

                boolean p16Finished = P16MultipassReflection.ready()
                        || P16MultipassReflection.failure() != null;
                int progress = 85; // Full lighting already includes player/entity ray tracing.
                if (p16Finished) progress += 15;
                yield progress;
            }
        };
    }

    public static void drawHud(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || !RendererSettings.rendererEnabled()) {
            return;
        }

        RendererState rendererState = RendererBootstrap.state();
        if (rendererState == RendererState.DISABLED_NON_VULKAN
                || rendererState == RendererState.DISABLED_UNSUPPORTED_VULKAN
                || rendererState == RendererState.PIPELINE_FAILED
                || P12FullBasePipeline.failure() != null) {
            return;
        }

        int progress = progressPercent();
        if (progress >= 100) {
            return;
        }

        String stageKey;
        if (rendererState == RendererState.WAITING_FOR_DEVICE
                || rendererState == RendererState.WAITING_FOR_VULKAN_INTEROP
                || rendererState == RendererState.NEW) {
            stageKey = "screen.totem-lumen.compile_hud.backend";
        } else if (rendererState == RendererState.WAITING_FOR_PIPELINE) {
            stageKey = "screen.totem-lumen.compile_hud.bootstrap";
        } else if (!P12FullBasePipeline.ready()) {
            stageKey = "screen.totem-lumen.compile_hud.full";
        } else {
            stageKey = "screen.totem-lumen.compile_hud.advanced";
        }

        Component label = Component.translatable(
                "screen.totem-lumen.compile_hud",
                Component.translatable(stageKey),
                progress
        );

        int x = 8;
        int y = 8;
        int barWidth = 132;
        int barHeight = 4;
        int textWidth = client.font.width(label);
        int panelWidth = Math.max(barWidth, textWidth);
        int barY = y + 12;
        int filledWidth = Math.round(barWidth * (progress / 100.0f));

        graphics.fill(x - 4, y - 4, x + panelWidth + 4, barY + barHeight + 4, 0xA0000000);
        graphics.text(client.font, label, x, y, 0xFFFFFFFF, true);
        graphics.fill(x, barY, x + barWidth, barY + barHeight, 0xA0404040);
        if (filledWidth > 0) {
            graphics.fill(x, barY, x + filledWidth, barY + barHeight, 0xFFE0E0E0);
        }
    }

    public static void reset() {
        phase = Phase.NONE;
        phaseStartedNanos = 0L;
        lastHeartbeatNanos = 0L;
        bootstrapReadyAnnounced = false;
        fullReadyAnnounced = false;
        p17ReadyAnnounced = false;
        p16ReadyAnnounced = false;
        fullFailureAnnounced = false;
        p17FailureAnnounced = false;
        p16FailureAnnounced = false;
        finalSummaryAnnounced = false;
        terminalStateAnnounced = null;
    }

    private static void announceTerminalState(Minecraft client, RendererState state) {
        if (terminalStateAnnounced == state) {
            return;
        }
        terminalStateAnnounced = state;
        phase = Phase.TERMINAL;
        String key = switch (state) {
            case DISABLED_NON_VULKAN -> "message.totem-lumen.compile.disabled_non_vulkan";
            case DISABLED_UNSUPPORTED_VULKAN -> "message.totem-lumen.compile.disabled_unsupported";
            case PIPELINE_FAILED -> "message.totem-lumen.compile.bootstrap_failed";
            default -> throw new IllegalArgumentException("Not a terminal renderer state: " + state);
        };
        send(client, key);
    }

    private static void enterPhase(Minecraft client, Phase next, long now, String key) {
        if (phase == next) {
            return;
        }
        phase = next;
        phaseStartedNanos = now;
        lastHeartbeatNanos = now;
        send(client, key);
    }

    private static void heartbeat(Minecraft client, long now, String key) {
        if (now - lastHeartbeatNanos < HEARTBEAT_NANOS) {
            return;
        }
        lastHeartbeatNanos = now;
        send(client, key, formatElapsed(now - phaseStartedNanos));
    }

    private static void heartbeat(Minecraft client, long now, String key, String stage) {
        if (now - lastHeartbeatNanos < HEARTBEAT_NANOS) {
            return;
        }
        lastHeartbeatNanos = now;
        send(client, key, formatElapsed(now - phaseStartedNanos), stage);
    }

    private static String formatElapsed(long elapsedNanos) {
        long totalSeconds = Math.max(0L, elapsedNanos / 1_000_000_000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes == 0L) {
            return seconds + "s";
        }
        return minutes + "m " + seconds + "s";
    }

    private static void send(Minecraft client, String translationKey, Object... args) {
        if (client.player != null) {
            client.player.sendSystemMessage(Component.translatable(translationKey, args));
        }
    }

    private enum Phase {
        NONE,
        BACKEND,
        BOOTSTRAP,
        FULL_BASE,
        ADVANCED,
        COMPLETE,
        DEGRADED,
        TERMINAL
    }
}
