package dev.totem.lumen.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.vulkan.resource.VulkanOwnedBuffer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Optional full P12-P15 replacement for the tiny renderer-readiness bootstrap pipeline.
 *
 * <p>MoltenVK cold compilation for the full base shader has measured in minutes on Apple Silicon.
 * The bootstrap pipeline therefore becomes ready first. This class compiles the full renderer on a
 * daemon worker and atomically substitutes it for frame dispatch only after the complete Vulkan
 * program exists. P17/P16 compilation begins only after this full base is ready, avoiding competing
 * long-running pipeline-cache builds during startup.</p>
 */
public final class P12FullBasePipeline {
    public static final String SHADER_NAME = "totem_lumen_p12_full_gi.comp";
    public static final String WORKER_NAME = "TotemLumen-P12FullPipeline";

    private static final Object LOCK = new Object();
    private static final ThreadLocal<VulkanComputeProgram> DISPATCH_PROGRAM = new ThreadLocal<>();

    private static volatile VulkanOwnedBuffer attachedScene;
    private static volatile VulkanComputeProgram activeProgram;
    private static volatile Throwable failure;
    private static volatile long generation;
    private static volatile boolean firstDispatchLogged;

    private P12FullBasePipeline() {
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
        closeAsync(staleProgram, "TotemLumen-P12FullOldPipelineCleanup");

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
                    "P12-P15 full pipeline creation START: shader={}, sourceChars={}; bootstrap renderer remains available during compile",
                    SHADER_NAME,
                    source.length()
            );
            created = VulkanComputeProgram.create(device, SHADER_NAME, source, scene);

            synchronized (LOCK) {
                if (workerGeneration != generation || attachedScene != scene) {
                    VulkanComputeProgram stale = created;
                    created = null;
                    closeAsync(stale, "TotemLumen-P12FullStalePipelineCleanup");
                    return;
                }
                activeProgram = created;
                created = null;
            }

            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            TotemLumenClient.LOGGER.info(
                    "P12-P15 full pipeline creation COMPLETE: shader={}, elapsed={} ms; full GI/environment renderer can now replace bootstrap",
                    SHADER_NAME,
                    elapsedMs
            );

            // Large optional enhancements start only after the full base is usable. This keeps
            // MoltenVK pipeline-cache creation serialized in useful dependency order.
            P17EnhancedBasePipeline.attach(device, scene);
            P16MultipassReflection.attach(device, scene);
        } catch (Throwable buildFailure) {
            synchronized (LOCK) {
                if (workerGeneration == generation && attachedScene == scene) {
                    failure = buildFailure;
                }
            }
            TotemLumenClient.LOGGER.error(
                    "P12-P15 full pipeline FAILED; keeping the bootstrap renderer active",
                    buildFailure
            );
        } finally {
            if (created != null) closeAsync(created, "TotemLumen-P12FullFailedPipelineCleanup");
        }
    }

    /** Builds P12-P15 plus exact P14E geometry, before optional P17/P14E optical rewrites. */
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

    /** Builds the exact pre-P17 production base source without using it as a readiness gate. */
    static String buildSourceForVerification() {
        return P14EFluidOpticsPatch.apply(buildGeometrySourceForVerification());
    }

    public static void beginDispatch() {
        DISPATCH_PROGRAM.set(activeProgram);
        VulkanComputeProgram selected = DISPATCH_PROGRAM.get();
        if (selected != null && !firstDispatchLogged) {
            firstDispatchLogged = true;
            TotemLumenClient.LOGGER.info(
                    "P12-P15 full renderer READY: full base pipeline selected instead of bootstrap"
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

    /**
     * Rebuilds the staged full-renderer pipelines for the currently attached scene.
     *
     * <p>The small bootstrap pipeline remains an internal compilation fallback while P12-P15
     * recompiles, but it is not composited to the player. Minecraft's normal world render remains
     * visible until the new full base succeeds. P17 and P16 are then restarted in normal order.</p>
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
                "Manual renderer pipeline recompile requested; Minecraft vanilla/resource-pack presentation remains visible until full base is ready"
        );
        shutdown();
        attach(device, scene);
        return true;
    }

    public static void shutdown() {
        P17EnhancedBasePipeline.shutdown();
        P16MultipassReflection.shutdown();

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
        closeAsync(program, "TotemLumen-P12FullShutdownCleanup");
    }

    private static void closeAsync(VulkanComputeProgram program, String threadName) {
        if (program == null) return;
        Thread cleanup = new Thread(() -> {
            try {
                program.close();
            } catch (Throwable closeFailure) {
                TotemLumenClient.LOGGER.warn("Failed to close stale full-base pipeline cleanly", closeFailure);
            }
        }, threadName);
        cleanup.setDaemon(true);
        cleanup.start();
    }
}
