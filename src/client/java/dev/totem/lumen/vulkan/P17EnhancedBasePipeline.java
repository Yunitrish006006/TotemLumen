package dev.totem.lumen.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.vulkan.resource.VulkanOwnedBuffer;

/**
 * Optional P17-enhanced replacement for the full P12-P15 compute program.
 *
 * <p>P17 never participates in renderer readiness. The tiny bootstrap becomes usable first, then
 * the full P12-P15 base, then this larger dynamic-entity variant. Command recording snapshots the
 * selected program once so pipeline/layout/descriptor reads for one frame cannot mix generations.</p>
 */
public final class P17EnhancedBasePipeline {
    public static final String SHADER_NAME = "totem_lumen_p17_dynamic_entities.comp";
    public static final String WORKER_NAME = "TotemLumen-P17Pipeline";

    private static final Object LOCK = new Object();
    private static final ThreadLocal<VulkanComputeProgram> DISPATCH_PROGRAM = new ThreadLocal<>();

    private static volatile VulkanOwnedBuffer attachedScene;
    private static volatile VulkanComputeProgram activeProgram;
    private static volatile Throwable failure;
    private static volatile long generation;
    private static volatile boolean firstDispatchLogged;

    private P17EnhancedBasePipeline() {
    }

    public static void attach(VulkanDevice device, VulkanOwnedBuffer scene) {
        if (device == null || scene == null) return;

        VulkanComputeProgram staleProgram;
        long workerGeneration;
        synchronized (LOCK) {
            if (attachedScene == scene && (activeProgram != null || failure == null)) return;
            staleProgram = activeProgram;
            activeProgram = null;
            attachedScene = scene;
            failure = null;
            firstDispatchLogged = false;
            workerGeneration = ++generation;
        }
        closeAsync(staleProgram, "TotemLumen-P17OldPipelineCleanup");

        Thread worker = new Thread(
                () -> buildPipeline(device, scene, workerGeneration),
                WORKER_NAME
        );
        worker.setDaemon(true);
        worker.start();
    }

    private static void buildPipeline(VulkanDevice device, VulkanOwnedBuffer scene, long workerGeneration) {
        VulkanComputeProgram created = null;
        long startedAt = System.nanoTime();
        try {
            String source = buildSourceForVerification();
            TotemLumenClient.LOGGER.info(
                    "P17 enhanced pipeline creation START: shader={}, sourceChars={}; P12-P15 full renderer remains available during compile",
                    SHADER_NAME,
                    source.length()
            );
            created = VulkanComputeProgram.create(device, SHADER_NAME, source, scene);

            synchronized (LOCK) {
                if (workerGeneration != generation || attachedScene != scene) {
                    VulkanComputeProgram stale = created;
                    created = null;
                    closeAsync(stale, "TotemLumen-P17StalePipelineCleanup");
                    return;
                }
                activeProgram = created;
                created = null;
            }

            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            TotemLumenClient.LOGGER.info(
                    "P17 enhanced pipeline creation COMPLETE: shader={}, elapsed={} ms; dynamic entities can now participate in shared nearest-hit tracing",
                    SHADER_NAME,
                    elapsedMs
            );
        } catch (Throwable buildFailure) {
            synchronized (LOCK) {
                if (workerGeneration == generation && attachedScene == scene) failure = buildFailure;
            }
            TotemLumenClient.LOGGER.error(
                    "P17 enhanced pipeline FAILED; keeping the P12-P15 full renderer active without dynamic-entity ray hits",
                    buildFailure
            );
        } finally {
            if (created != null) closeAsync(created, "TotemLumen-P17FailedPipelineCleanup");
        }
    }

    static String buildSourceForVerification() {
        String p17Geometry = P17ShaderIntegration.apply(P12FullBasePipeline.buildGeometrySourceForVerification());
        return P14EFluidOpticsPatch.apply(p17Geometry);
    }

    /** Snapshot full-base and P17 programs once for one command-recording operation. */
    public static void beginDispatch() {
        P12FullBasePipeline.beginDispatch();
        DISPATCH_PROGRAM.set(activeProgram);
        VulkanComputeProgram selected = DISPATCH_PROGRAM.get();
        if (selected != null && !firstDispatchLogged) {
            firstDispatchLogged = true;
            TotemLumenClient.LOGGER.info(
                    "P17 dynamic entity tracing READY: enhanced base pipeline selected for frame dispatch"
            );
        }
    }

    public static VulkanComputeProgram selectForCurrentDispatch(VulkanComputeProgram bootstrap) {
        VulkanComputeProgram selected = DISPATCH_PROGRAM.get();
        if (selected != null) return selected;
        return P12FullBasePipeline.selectForCurrentDispatch(bootstrap);
    }

    public static void endDispatch() {
        DISPATCH_PROGRAM.remove();
        P12FullBasePipeline.endDispatch();
    }

    public static boolean ready() {
        return activeProgram != null;
    }

    public static Throwable failure() {
        return failure;
    }

    public static void shutdown() {
        VulkanComputeProgram program;
        synchronized (LOCK) {
            ++generation;
            attachedScene = null;
            program = activeProgram;
            activeProgram = null;
            failure = null;
            firstDispatchLogged = false;
        }
        DISPATCH_PROGRAM.remove();
        closeAsync(program, "TotemLumen-P17ShutdownCleanup");
    }

    private static void closeAsync(VulkanComputeProgram program, String threadName) {
        if (program == null) return;
        Thread cleanup = new Thread(() -> {
            try {
                program.close();
            } catch (Throwable closeFailure) {
                TotemLumenClient.LOGGER.warn("Failed to close stale P17 pipeline cleanly", closeFailure);
            }
        }, threadName);
        cleanup.setDaemon(true);
        cleanup.start();
    }
}
