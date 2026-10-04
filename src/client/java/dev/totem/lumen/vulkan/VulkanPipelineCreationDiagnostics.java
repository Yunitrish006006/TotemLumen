package dev.totem.lumen.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineCreationFeedback;
import org.lwjgl.vulkan.VkPipelineCreationFeedbackCreateInfo;

import java.nio.LongBuffer;

/** Creation-only diagnostics shared by the renderer and the isolated native probe. */
final class VulkanPipelineCreationDiagnostics {
    record Result(int vkResult, long elapsedNs, boolean feedbackRequested, boolean feedbackValid,
                  boolean applicationCacheHit, long driverDurationNs) {
        String hitLabel() {
            return feedbackValid ? Boolean.toString(applicationCacheHit) : "unknown";
        }
    }

    static boolean canRequest(VkDevice device, VkComputePipelineCreateInfo.Buffer infos) {
        // Do not replace an existing extension chain or attach duplicate feedback.
        return infos.remaining() == 1 && infos.get(infos.position()).pNext() == 0L
                && (device.getCapabilities().Vulkan13
                || device.getCapabilities().VK_EXT_pipeline_creation_feedback);
    }

    static Result create(VkDevice device, long cache, VkComputePipelineCreateInfo.Buffer infos,
                         LongBuffer output) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean requested = canRequest(device, infos);
            VkComputePipelineCreateInfo info = infos.get(infos.position());
            VkPipelineCreationFeedback feedback = null;
            if (requested) {
                feedback = VkPipelineCreationFeedback.create(stack.ncalloc(
                        VkPipelineCreationFeedback.ALIGNOF, 1, VkPipelineCreationFeedback.SIZEOF));
                var feedbackInfo = VkPipelineCreationFeedbackCreateInfo.calloc(stack).sType$Default()
                        .pPipelineCreationFeedback(feedback);
                info.pNext(feedbackInfo.address());
            }
            int result;
            long started = System.nanoTime();
            try {
                result = VK10.vkCreateComputePipelines(device, cache, infos, null, output);
            } finally {
                // Never leave a stack address attached to caller-owned input.
                if (requested) info.pNext(0L);
            }
            long elapsed = System.nanoTime() - started;
            boolean valid = requested
                    && (feedback.flags() & VK13.VK_PIPELINE_CREATION_FEEDBACK_VALID_BIT) != 0;
            boolean hit = valid
                    && (feedback.flags() & VK13.VK_PIPELINE_CREATION_FEEDBACK_APPLICATION_PIPELINE_CACHE_HIT_BIT) != 0;
            return new Result(result, elapsed, requested, valid, hit, valid ? feedback.duration() : 0L);
        }
    }

    private VulkanPipelineCreationDiagnostics() {}
}
