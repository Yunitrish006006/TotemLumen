package dev.totem.lumen.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.render.RendererSettings;
import dev.totem.lumen.vulkan.resource.VulkanOwnedBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Owns the split reflection pipeline with menu-time prewarming and scene-time binding. */
public final class P16MultipassReflection {
    public static final String WORKER_NAME = "TotemLumen-P16Pipeline";

    private static final Object LOCK = new Object();

    private static volatile VulkanOwnedBuffer attachedScene;
    private static volatile VulkanComputeProgram activeProgram;
    private static volatile VulkanComputeProgram.PreparedPipeline preparedPipeline;
    private static volatile VulkanDevice preparedDevice;
    private static volatile Throwable failure;
    private static volatile Throwable prewarmFailure;
    private static volatile long generation;
    private static volatile boolean prewarmStarted;
    private static volatile boolean firstDispatchLogged;

    private P16MultipassReflection() {
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

        VulkanDeviceLifetime.startWorker(device, WORKER_NAME,
                () -> buildPreparedPipeline(device, workerGeneration));
    }

    private static void buildPreparedPipeline(VulkanDevice device, long workerGeneration) {
        VulkanComputeProgram.PreparedPipeline created = null;
        long startedAt = System.nanoTime();
        try {
            String source = buildRuntimeSource();
            TotemLumenClient.LOGGER.info(
                    "Reflection pipeline prewarm START: shader={}, sourceChars={}",
                    P16ReflectionPassShader.SHADER_NAME,
                    source.length()
            );
            created = VulkanComputeProgram.preparePipeline(
                    device,
                    P16ReflectionPassShader.SHADER_NAME,
                    source
            );

            synchronized (LOCK) {
                if (workerGeneration != generation
                        || preparedDevice == null
                        || preparedDevice.vkDevice() != device.vkDevice()) {
                    VulkanComputeProgram.PreparedPipeline stale = created;
                    created = null;
                    closePreparedAsync(stale, "TotemLumen-P16StalePreparedCleanup");
                    return;
                }
                preparedPipeline = created;
                created = null;
                prewarmStarted = false;
                LOCK.notifyAll();
            }

            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            TotemLumenClient.LOGGER.info(
                    "Reflection pipeline prewarm COMPLETE: shader={}, elapsed={} ms",
                    P16ReflectionPassShader.SHADER_NAME,
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
            TotemLumenClient.LOGGER.error("Reflection pipeline prewarm FAILED", buildFailure);
        } finally {
            closePreparedAsync(created, "TotemLumen-P16FailedPreparedCleanup");
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
        closeAsync(staleProgram, "TotemLumen-P16OldBindingCleanup");

        VulkanDeviceLifetime.startWorker(device, "TotemLumen-P16Bind",
                () -> bindPreparedPipeline(device, scene, workerGeneration));
    }

    /** Drop a scene binding before its storage buffer is destroyed; retain the prepared pipeline. */
    public static void detach(VulkanOwnedBuffer scene) {
        VulkanComputeProgram staleProgram;
        synchronized (LOCK) {
            if (scene == null || attachedScene != scene) return;
            attachedScene = null;
            staleProgram = activeProgram;
            activeProgram = null;
            failure = null;
            firstDispatchLogged = false;
            LOCK.notifyAll();
        }
        closeAsync(staleProgram, "TotemLumen-P16DetachedBindingCleanup");
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
                    throw new IllegalStateException("Reflection pipeline prewarm failed", prewarmFailure);
                }
                prepared = preparedPipeline;
            }

            synchronized (LOCK) {
                if (workerGeneration != generation || attachedScene != scene) return;
                // detach() holds the same lock before the caller closes scene storage.
                created = prepared.bind(device, scene);
                activeProgram = created;
                created = null;
                TotemLumenClient.LOGGER.info("Reflection scene binding READY");
            }
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
                    "Reflection scene binding FAILED; base renderer remains active",
                    bindFailure
            );
        } finally {
            closeAsync(created, "TotemLumen-P16FailedBindingCleanup");
        }
    }

    public static void recordAfterBaseDispatch(
            VkCommandBuffer commandBuffer,
            int groupCountX,
            int groupCountY,
            int groupCountZ
    ) {
        VulkanComputeProgram program = activeProgram;
        VulkanOwnedBuffer scene = attachedScene;
        if (program == null || scene == null || !RendererSettings.reflectionsEnabled()) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferMemoryBarrier.Buffer baseToReflection = VkBufferMemoryBarrier.calloc(1, stack);
            baseToReflection.get(0)
                    .sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(scene.vkBuffer())
                    .offset(0)
                    .size(scene.size());
            VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0,
                    null,
                    baseToReflection,
                    null
            );

            VK10.vkCmdBindPipeline(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, program.pipeline());
            VK10.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    program.pipelineLayout(),
                    0,
                    stack.longs(program.descriptorSet()),
                    null
            );
            VK10.vkCmdDispatch(commandBuffer, groupCountX, groupCountY, groupCountZ);
        }

        if (!firstDispatchLogged) {
            firstDispatchLogged = true;
            TotemLumenClient.LOGGER.info(
                    "P16 multipass reflection READY: base=P12-P15+P14E+P17, reflection=separate-compute-pass+P14E+P17, maxBounces={}, maxDistance={}",
                    RendererSettings.reflectionBounces(),
                    RendererSettings.reflectionDistance()
            );
        }
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
        closeAsync(program, "TotemLumen-P16ShutdownBindingCleanup");
        closePreparedAsync(prepared, "TotemLumen-P16ShutdownPreparedCleanup");
    }

    static String buildSourceForVerification() {
        return P14EFluidOpticsPatch.apply(P17ShaderIntegration.apply(
                P14EFluidShaderPatch.apply(P16ReflectionPassShader.build())));
    }

    static String buildRuntimeSource() {
        return LumenShaderVariant.runtimeSource("reflection", buildSourceForVerification());
    }

    private static void closeAsync(VulkanComputeProgram program, String threadName) {
        if (program == null) return;
        try {
            program.close();
        } catch (Throwable closeFailure) {
            TotemLumenClient.LOGGER.warn("Failed to retire P16 binding: " + threadName, closeFailure);
        }
    }

    private static void closePreparedAsync(
            VulkanComputeProgram.PreparedPipeline prepared,
            String threadName
    ) {
        if (prepared == null) return;
        try {
            prepared.close();
        } catch (Throwable closeFailure) {
            TotemLumenClient.LOGGER.warn("Failed to retire reflection pipeline: " + threadName, closeFailure);
        }
    }
}
