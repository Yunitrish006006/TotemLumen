package dev.totem.lumen.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.vulkan.resource.VulkanOwnedBuffer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Full material-aware base pipeline.
 *
 * <p>The expensive shader/pipeline build is prewarmed as soon as Minecraft's Vulkan device is
 * available. World entry only binds the already-prepared pipeline to the live scene storage buffer,
 * so pipeline compilation no longer has to wait for scene allocation.</p>
 */
public final class P12FullBasePipeline {
    public static final String SHADER_NAME = "totem_lumen_p12_full_gi.comp";
    public static final String WORKER_NAME = "TotemLumen-P12FullPipeline";

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

    private P12FullBasePipeline() {
    }

    /**
     * Starts expensive GLSL -> SPIR-V -> Vulkan pipeline preparation without requiring a world.
     */
    public static void prewarm(VulkanDevice device) {
        if (device == null) return;

        VulkanComputeProgram.PreparedPipeline stalePrepared = null;
        long workerGeneration;
        synchronized (LOCK) {
            if (preparedDevice != null && preparedDevice.vkDevice() != device.vkDevice()) {
                ++generation;
                stalePrepared = preparedPipeline;
                preparedPipeline = null;
                preparedDevice = null;
                prewarmStarted = false;
                prewarmFailure = null;
                LOCK.notifyAll();
            }

            if (preparedPipeline != null || prewarmStarted) {
                closePreparedAsync(stalePrepared, "TotemLumen-P12FullOldPreparedCleanup");
                return;
            }

            preparedDevice = device;
            prewarmStarted = true;
            prewarmFailure = null;
            workerGeneration = generation;
        }
        closePreparedAsync(stalePrepared, "TotemLumen-P12FullOldPreparedCleanup");

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
                    "Full lighting pipeline prewarm START: shader={}, sourceChars={}",
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
                    closePreparedAsync(stale, "TotemLumen-P12FullStalePreparedCleanup");
                    return;
                }
                preparedPipeline = created;
                created = null;
                prewarmStarted = false;
                LOCK.notifyAll();
            }

            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            TotemLumenClient.LOGGER.info(
                    "Full lighting pipeline prewarm COMPLETE: shader={}, elapsed={} ms",
                    SHADER_NAME,
                    elapsedMs
            );

            // Dynamic-entity nearest-hit tracing is compiled directly into this one production
            // lighting pipeline. Reflection remains an independent pass and may prewarm now.
            P16MultipassReflection.prewarm(device);
        } catch (Throwable buildFailure) {
            synchronized (LOCK) {
                if (workerGeneration == generation) {
                    prewarmFailure = buildFailure;
                    prewarmStarted = false;
                    LOCK.notifyAll();
                }
            }
            TotemLumenClient.LOGGER.error(
                    "Full lighting pipeline prewarm FAILED",
                    buildFailure
            );
        } finally {
            closePreparedAsync(created, "TotemLumen-P12FullFailedPreparedCleanup");
        }
    }

    /**
     * Binds the prewarmed pipeline to the live world scene. If prewarm is still running this waits
     * only on a daemon worker; the render thread remains on Minecraft's normal presentation.
     */
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
        closeAsync(staleProgram, "TotemLumen-P12FullOldBindingCleanup");

        Thread worker = new Thread(
                () -> bindPreparedPipeline(device, scene, workerGeneration),
                "TotemLumen-P12FullBind"
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
                            "Full lighting pipeline prewarm failed",
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
                    closeAsync(stale, "TotemLumen-P12FullStaleBindingCleanup");
                    return;
                }
                activeProgram = created;
                created = null;
            }

            TotemLumenClient.LOGGER.info(
                    "Full lighting scene binding READY; prewarmed pipeline is available for frame dispatch"
            );
            P16MultipassReflection.attach(device, scene);
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
                    "Full lighting scene binding FAILED; Minecraft presentation remains active",
                    bindFailure
            );
        } finally {
            closeAsync(created, "TotemLumen-P12FullFailedBindingCleanup");
        }
    }

    /** Builds the shared static/fluid geometry source before entity nearest-hit and optics rewrites. */
    static String buildGeometrySourceForVerification() {
        try {
            Field shaderField = P5StableLookupRenderer.class.getDeclaredField("SHADER");
            shaderField.setAccessible(true);
            String source = (String) shaderField.get(null);

            Method transform = VulkanComputeProgram.class.getDeclaredMethod(
                    "transformMainGiShader",
                    String.class
            );
            transform.setAccessible(true);
            String transformed = (String) transform.invoke(null, source);
            return P14EFluidShaderPatch.apply(transformed);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Failed to build full P12-P15 geometry shader", failure);
        }
    }

    /** Builds the single production lighting source, including dynamic-entity nearest-hit tracing. */
    static String buildSourceForVerification() {
        String entityAwareGeometry = P17ShaderIntegration.apply(buildGeometrySourceForVerification());
        return P14EFluidOpticsPatch.apply(entityAwareGeometry);
    }


    public static void beginDispatch() {
        DISPATCH_PROGRAM.set(activeProgram);
        VulkanComputeProgram selected = DISPATCH_PROGRAM.get();
        if (selected != null && !firstDispatchLogged) {
            firstDispatchLogged = true;
            TotemLumenClient.LOGGER.info(
                    "Full lighting renderer READY: static world, fluids, materials and dynamic entities share one pipeline"
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
        Throwable bindingFailure = failure;
        return bindingFailure != null ? bindingFailure : prewarmFailure;
    }


    /**
     * Rebuilds the full renderer pipelines for the currently attached scene.
     *
     * <p>Manual recompilation invalidates both prepared pipelines and scene bindings. Minecraft's
     * normal presentation remains visible while a fresh prewarm is produced.</p>
     *
     * @return true when a rebuild was started; false when no live Vulkan scene is attached
     */
    public static boolean recompile() {
        VulkanDevice device = MinecraftVulkanBridge.currentDevice();
        VulkanOwnedBuffer scene = attachedScene;
        if (device == null || scene == null) {
            TotemLumenClient.LOGGER.warn(
                    "Pipeline recompile requested before a live Vulkan scene was attached"
            );
            return false;
        }

        TotemLumenClient.LOGGER.info(
                "Manual renderer pipeline recompile requested; Minecraft presentation remains visible until full lighting is ready"
        );
        shutdown();
        prewarm(device);
        attach(device, scene);
        return true;
    }

    public static void shutdown() {
        P16MultipassReflection.shutdown();

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
        closeAsync(program, "TotemLumen-P12FullShutdownBindingCleanup");
        closePreparedAsync(prepared, "TotemLumen-P12FullShutdownPreparedCleanup");
    }

    private static void closeAsync(VulkanComputeProgram program, String threadName) {
        if (program == null) return;
        Thread cleanup = new Thread(() -> {
            try {
                program.close();
            } catch (Throwable closeFailure) {
                TotemLumenClient.LOGGER.warn("Failed to close stale full-base binding cleanly", closeFailure);
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
                TotemLumenClient.LOGGER.warn("Failed to close prepared full-base pipeline cleanly", closeFailure);
            }
        }, threadName);
        cleanup.setDaemon(true);
        cleanup.start();
    }
}
