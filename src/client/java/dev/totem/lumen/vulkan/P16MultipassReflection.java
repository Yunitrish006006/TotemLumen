package dev.totem.lumen.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.render.RendererSettings;
import dev.totem.lumen.vulkan.resource.VulkanOwnedBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Owns the split P16 reflection pipeline. */
public final class P16MultipassReflection {
    private static final Object LOCK = new Object();

    private static volatile VulkanOwnedBuffer attachedScene;
    private static volatile VulkanComputeProgram activeProgram;
    private static volatile Throwable failure;
    private static volatile long generation;
    private static volatile boolean firstDispatchLogged;

    private P16MultipassReflection() {
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
        closeAsync(staleProgram, "TotemLumen-P16OldPipelineCleanup");

        Thread worker = new Thread(() -> buildPipeline(device, scene, workerGeneration), "TotemLumen-P16Pipeline");
        worker.setDaemon(true);
        worker.start();
    }

    private static void buildPipeline(VulkanDevice device, VulkanOwnedBuffer scene, long workerGeneration) {
        VulkanComputeProgram created = null;
        long startedAt = System.nanoTime();
        try {
            String geometrySource = P14EFluidShaderPatch.apply(P16ReflectionPassShader.build());
            String entitySource = P17ShaderIntegration.apply(geometrySource);
            String source = P14EFluidOpticsPatch.apply(entitySource);
            TotemLumenClient.LOGGER.info(
                    "P16 split pipeline creation START: shader={}, sourceChars={}",
                    P16ReflectionPassShader.SHADER_NAME,
                    source.length()
            );
            created = VulkanComputeProgram.create(
                    device,
                    P16ReflectionPassShader.SHADER_NAME,
                    source,
                    scene
            );

            synchronized (LOCK) {
                if (workerGeneration != generation || attachedScene != scene) {
                    VulkanComputeProgram stale = created;
                    created = null;
                    closeAsync(stale, "TotemLumen-P16StalePipelineCleanup");
                    return;
                }
                activeProgram = created;
                created = null;
            }

            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            TotemLumenClient.LOGGER.info(
                    "P16 split pipeline creation COMPLETE: shader={}, elapsed={} ms; base renderer remained available during compile",
                    P16ReflectionPassShader.SHADER_NAME,
                    elapsedMs
            );
        } catch (Throwable buildFailure) {
            synchronized (LOCK) {
                if (workerGeneration == generation && attachedScene == scene) failure = buildFailure;
            }
            TotemLumenClient.LOGGER.error(
                    "P16 split reflection pipeline FAILED; keeping P12-P15 base renderer active without reflections",
                    buildFailure
            );
        } finally {
            if (created != null) closeAsync(created, "TotemLumen-P16FailedPipelineCleanup");
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
        closeAsync(program, "TotemLumen-P16ShutdownCleanup");
    }

    private static void closeAsync(VulkanComputeProgram program, String threadName) {
        if (program == null) return;
        Thread cleanup = new Thread(() -> {
            try {
                program.close();
            } catch (Throwable closeFailure) {
                TotemLumenClient.LOGGER.warn("Failed to close stale P16 pipeline cleanly", closeFailure);
            }
        }, threadName);
        cleanup.setDaemon(true);
        cleanup.start();
    }
}
