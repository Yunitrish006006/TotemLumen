package dev.totem.lumen.render;

import dev.totem.lumen.vulkan.P12FullBasePipeline;
import dev.totem.lumen.vulkan.P16MultipassReflection;
import dev.totem.lumen.vulkan.P17EnhancedBasePipeline;
import net.minecraft.client.Minecraft;
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
            heartbeat(client, now, "message.totem-lumen.compile.full_wait");
            return;
        }

        if (!fullReadyAnnounced) {
            fullReadyAnnounced = true;
            send(client, "message.totem-lumen.compile.full_ready");
        }

        boolean p17Ready = P17EnhancedBasePipeline.ready();
        boolean p16Ready = P16MultipassReflection.ready();
        Throwable p17Failure = P17EnhancedBasePipeline.failure();
        Throwable p16Failure = P16MultipassReflection.failure();

        if (!p17Ready && p17Failure != null && !p17FailureAnnounced) {
            p17FailureAnnounced = true;
            send(client, "message.totem-lumen.compile.p17_failed");
        }
        if (!p16Ready && p16Failure != null && !p16FailureAnnounced) {
            p16FailureAnnounced = true;
            send(client, "message.totem-lumen.compile.p16_failed");
        }

        if (p17Ready && !p17ReadyAnnounced) {
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
