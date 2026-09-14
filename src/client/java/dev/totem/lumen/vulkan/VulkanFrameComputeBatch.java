package dev.totem.lumen.vulkan;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;

/**
 * One Totem Lumen compute command buffer integrated into Minecraft's existing graphics submission.
 *
 * <p>This is the preferred baseline seam on devices whose graphics queue supports compute. It keeps
 * ordering inside Minecraft's own VulkanCommandEncoder instead of creating a second submission
 * timeline or blocking with vkQueueWaitIdle. The command buffer is allocated from Minecraft's
 * transient pool and Minecraft also owns its deferred destruction.</p>
 *
 * <p>Instances must be created/finished from the same render-thread context used by Minecraft's
 * VulkanCommandEncoder.</p>
 */
public final class VulkanFrameComputeBatch {
    private final VulkanCommandEncoder encoder;
    private final VkCommandBuffer commandBuffer;
    private boolean finished;

    private VulkanFrameComputeBatch(VulkanCommandEncoder encoder, VkCommandBuffer commandBuffer) {
        this.encoder = encoder;
        this.commandBuffer = commandBuffer;
    }

    public static VulkanFrameComputeBatch begin(VulkanDevice device, VulkanCapabilities capabilities) {
        if (!capabilities.canUseMinecraftFrameSubmissionForCompute()) {
            throw new IllegalStateException(
                    "Minecraft graphics queue does not advertise VK_QUEUE_COMPUTE_BIT"
            );
        }

        VulkanCommandEncoder encoder = device.createCommandEncoder();
        VkCommandBuffer commandBuffer = encoder.allocateTransientCommandBuffer(true);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            int result = VK10.vkBeginCommandBuffer(commandBuffer, beginInfo);
            if (result != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkBeginCommandBuffer failed with VkResult " + result);
            }
        }

        return new VulkanFrameComputeBatch(encoder, commandBuffer);
    }

    /** Native Vulkan commands may be recorded against this command buffer until finishAndEnqueue. */
    public VkCommandBuffer commandBuffer() {
        if (finished) {
            throw new IllegalStateException("Compute batch is already finished");
        }
        return commandBuffer;
    }

    /**
     * Ends recording and appends this command buffer to Minecraft's current graphics submission.
     * This does not block the CPU or wait for the queue to become idle.
     */
    public void finishAndEnqueue() {
        if (finished) {
            throw new IllegalStateException("Compute batch is already finished");
        }

        int result = VK10.vkEndCommandBuffer(commandBuffer);
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer failed with VkResult " + result);
        }
        encoder.execute(commandBuffer);
        finished = true;
    }

    public boolean isFinished() {
        return finished;
    }
}
