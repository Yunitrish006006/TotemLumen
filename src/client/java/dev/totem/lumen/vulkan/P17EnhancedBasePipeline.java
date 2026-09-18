package dev.totem.lumen.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.vulkan.resource.VulkanOwnedBuffer;

/**
 * Optional dynamic-entity enhanced base pipeline.
 *
 * <p>The expensive pipeline is prepared without a world as soon as the full-lighting prewarm
 * completes. World scene attachment only creates the descriptor binding.</p>
 */
public final class P17EnhancedBasePipeline {
    public static final String SHADER_NAME = "totem_lumen_p17_dynamic_entities.comp";
    public static final String WORKER_NAME = "TotemLumen-P17Pipeline";

    private static final Object LOCK = new Object();
    private static final ThreadLocal<VulkanComputeProgram> DISPATCH_PROGRAM = new ThreadLocal<>();

    private static volatile VulkanOwnedBuffer attachedScene;
    private static volatile VulkanComputeProgram activeProgram;
    private static volatile VulkanComputeProgram.PreparedPipeline preparedPipeline;
    private static volatile VulkanDevice preparedDevice;
    private static volatile Throwable failure;
    private static volatile Throwable prewarmFailure;
    private static volatile long generation;
    private static volatile boolean prewarmStarted;
    private static volatile boolean firstDispatchLogged;

    private P17EnhancedBasePipeline() {
    }

    public static void prewarm(VulkanDevice device) {
        if (device == null) return;

        long workerGeneration;
        synchronized (LOCK) {
            if (preparedPipeline != null
                    && preparedDevice != null
                    && preparedDevice.vkDevice() == device.vkDevice()) {
                return;
            }
            if (prewarmStarted
                    && preparedDevice != null
                    && preparedDevice.vkDevice() == device.vkDevice()) {
                return;
            }
            if (preparedDevice != null && preparedDevice.vkDevice() != device.vkDevice()) {
                ++generation;
                preparedPipeline = null;
                prewarmFailure = null;
            }
            preparedDevice = device;
            prewarmStarted = true;
            prewarmFailure = null;
            workerGeneration = generation;
        }

        Thread worker = new Thread(
                () -> buildPreparedPipeline(device, workerGeneration),
                WORKER_NAME
        );
        worker.setDaemon(true);
        worker.start();
    }

    private static void buildPreparedPipeline(VulkanDevice device, long workerGeneration) {
        VulkanComputeProgram.PreparedPipeline created = null;
        long startedAt = System.nanoTime();
        try {
            String source = buildSourceForVerification();
            TotemLumenClient.LOGGER.info(
                    "Player/entity ray tracing prewarm START: shader={}, sourceChars={}",
                    SHADER_NAME,
                    source.length()
            );
            created = VulkanComputeProgram.preparePipeline(device, SHADER_NAME, source);

            synchronized (LOCK) {
                if (workerGeneration != generation
                        || preparedDevice == null
                        || preparedDevice.vkDevice() != device.vkDevice()) {
                    VulkanComputeProgram.PreparedPipeline stale = created;
                    created = null;
                    closePreparedAsync(stale, "TotemLumen-P17StalePreparedCleanup");
                    return;
                }
                preparedPipeline = created;
                created = null;
                prewarmStarted = false;
                LOCK.notifyAll();
            }

            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            TotemLumenClient.LOGGER.info(
                    "Player/entity ray tracing prewarm COMPLETE: shader={}, elapsed={} ms",
                    SHADER_NAME,
                    elapsedMs
            );
        } catch (Throwable buildFailure) {
            synchronized (LOCK) {
                if (workerGeneration == generation) {
                    prewarmFailure = buildFailure;
                    prewarmStarted = false;
                    LOCK.notifyAll();
                }
            }
            TotemLumenClient.LOGGER.error(
                    "Player/entity ray tracing prewarm FAILED",
                    buildFailure
            );
        } finally {
            closePreparedAsync(created, "TotemLumen-P17FailedPreparedCleanup");
        }
    }

    public static void attach(VulkanDevice device, VulkanOwnedBuffer scene) {
        if (device == null || scene == null) return;
        prewarm(device);

        VulkanComputeProgram staleProgram;
        long workerGeneration;
        synchronized (LOCK) {
            if (attachedScene == scene && activeProgram != null) return;
            staleProgram = activeProgram;
            activeProgram = null;
            attachedScene = scene;
            failure = null;
            firstDispatchLogged = false;
            workerGeneration = generation;
        }
        closeAsync(staleProgram, "TotemLumen-P17OldBindingCleanup");

        Thread worker = new Thread(
                () -> bindPreparedPipeline(device, scene, workerGeneration),
                "TotemLumen-P17Bind"
        );
        worker.setDaemon(true);
        worker.start();
    }

    private static void bindPreparedPipeline(
            VulkanDevice device,
            VulkanOwnedBuffer scene,
            long workerGeneration
    ) {
        VulkanComputeProgram created = null;
        try {
            VulkanComputeProgram.PreparedPipeline prepared;
            synchronized (LOCK) {
                while (workerGeneration == generation
                        && attachedScene == scene
                        && preparedPipeline == null
                        && prewarmFailure == null) {
                    LOCK.wait();
                }
                if (workerGeneration != generation || attachedScene != scene) {
                    return;
                }
                if (prewarmFailure != null) {
                    throw new IllegalStateException(
                            "Player/entity ray tracing prewarm failed",
                            prewarmFailure
                    );
                }
                prepared = preparedPipeline;
            }

            created = prepared.bind(device, scene);
            synchronized (LOCK) {
                if (workerGeneration != generation || attachedScene != scene) {
                    VulkanComputeProgram stale = created;
                    created = null;
                    closeAsync(stale, "TotemLumen-P17StaleBindingCleanup");
                    return;
                }
                activeProgram = created;
                created = null;
            }

            TotemLumenClient.LOGGER.info(
                    "Player/entity ray tracing scene binding READY"
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            synchronized (LOCK) {
                if (workerGeneration == generation && attachedScene == scene) {
                    failure = interrupted;
                }
            }
        } catch (Throwable bindFailure) {
            synchronized (LOCK) {
                if (workerGeneration == generation && attachedScene == scene) {
                    failure = bindFailure;
                }
            }
            TotemLumenClient.LOGGER.error(
                    "Player/entity ray tracing scene binding FAILED; full static lighting remains active",
                    bindFailure
            );
        } finally {
            closeAsync(created, "TotemLumen-P17FailedBindingCleanup");
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
        Throwable bindingFailure = failure;
        return bindingFailure != null ? bindingFailure : prewarmFailure;
    }


    public static void shutdown() {
        VulkanComputeProgram program;
        VulkanComputeProgram.PreparedPipeline prepared;
        synchronized (LOCK) {
            ++generation;
            attachedScene = null;
            program = activeProgram;
            activeProgram = null;
            prepared = preparedPipeline;
            preparedPipeline = null;
            preparedDevice = null;
            failure = null;
            prewarmFailure = null;
            prewarmStarted = false;
            firstDispatchLogged = false;
            LOCK.notifyAll();
        }
        DISPATCH_PROGRAM.remove();
        closeAsync(program, "TotemLumen-P17ShutdownBindingCleanup");
        closePreparedAsync(prepared, "TotemLumen-P17ShutdownPreparedCleanup");
    }

    private static void closeAsync(VulkanComputeProgram program, String threadName) {
        if (program == null) return;
        Thread cleanup = new Thread(() -> {
            try {
                program.close();
            } catch (Throwable closeFailure) {
                TotemLumenClient.LOGGER.warn("Failed to close stale P17 binding cleanly", closeFailure);
            }
        }, threadName);
        cleanup.setDaemon(true);
        cleanup.start();
    }

    private static void closePreparedAsync(
            VulkanComputeProgram.PreparedPipeline prepared,
            String threadName
    ) {
        if (prepared == null) return;
        Thread cleanup = new Thread(() -> {
            try {
                prepared.close();
            } catch (Throwable closeFailure) {
                TotemLumenClient.LOGGER.warn("Failed to close prepared P17 pipeline cleanly", closeFailure);
            }
        }, threadName);
        cleanup.setDaemon(true);
        cleanup.start();
    }
}
