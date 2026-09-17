package dev.totem.lumen.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.vulkan.resource.VulkanOwnedBuffer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Optional P17-enhanced replacement for the P12-P15 base compute program.
 *
 * <p>Apple Silicon + MoltenVK has already shown that monolithic compute pipelines around the old
 * P16 size can take minutes to compile. Dynamic-entity tracing therefore must never participate in
 * renderer readiness. The proven P12-P15 program becomes ready first; this class builds the larger
 * P17 variant on a daemon worker and atomically substitutes it at command-record time only after the
 * whole Vulkan program is ready.</p>
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
            if (attachedScene == scene && (activeProgram != null || failure == null)) {
                return;
            }
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
                    "P17 enhanced pipeline creation START: shader={}, sourceChars={}; base renderer remains available during compile",
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
                if (workerGeneration == generation && attachedScene == scene) {
                    failure = buildFailure;
                }
            }
            TotemLumenClient.LOGGER.error(
                    "P17 enhanced pipeline FAILED; keeping the P12-P15 base renderer active without dynamic-entity ray hits",
                    buildFailure
            );
        } finally {
            if (created != null) closeAsync(created, "TotemLumen-P17FailedPipelineCleanup");
        }
    }

    /**
     * Builds the exact P17 source from the same private production transform used by the readiness
     * pipeline, then adds only the dynamic-entity nearest-hit layer.
     */
    static String buildSourceForVerification() {
        try {
            Field shaderField = P5StableLookupRenderer.class.getDeclaredField("SHADER");
            shaderField.setAccessible(true);
            String source = (String) shaderField.get(null);

            Method transform = VulkanComputeProgram.class.getDeclaredMethod(
                    "transformMainGiShader",
                    String.class
            );
            transform.setAccessible(true);
            String baseSource = (String) transform.invoke(null, source);
            return P17ShaderIntegration.apply(baseSource);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Failed to build P17 enhanced production shader", failure);
        }
    }

    /** Snapshot the optional enhanced program once so one command recording cannot mix pipelines. */
    public static void beginDispatch() {
        DISPATCH_PROGRAM.set(activeProgram);
        VulkanComputeProgram selected = DISPATCH_PROGRAM.get();
        if (selected != null && !firstDispatchLogged) {
            firstDispatchLogged = true;
            TotemLumenClient.LOGGER.info(
                    "P17 dynamic entity tracing READY: enhanced base pipeline selected for frame dispatch"
            );
        }
    }

    public static VulkanComputeProgram selectForCurrentDispatch(VulkanComputeProgram fallback) {
        VulkanComputeProgram selected = DISPATCH_PROGRAM.get();
        return selected == null ? fallback : selected;
    }

    public static void endDispatch() {
        DISPATCH_PROGRAM.remove();
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
