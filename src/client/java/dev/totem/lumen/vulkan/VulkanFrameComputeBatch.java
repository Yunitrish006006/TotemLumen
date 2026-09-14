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
 * <p>Totem Lumen owns the command pool/buffer, but Minecraft's VulkanCommandEncoder owns queue
 * submission ordering. Completion is handed back through Minecraft's fence queue, so the CPU never
 * performs a per-frame queue-idle wait.</p>
 */
public final class VulkanFrameComputeBatch {
    private final VulkanCommandEncoder encoder;
    private final VulkanFrameCommandPool commandPool;
    private final VkCommandBuffer commandBuffer;
    private boolean finished;

    private VulkanFrameComputeBatch(
            VulkanCommandEncoder encoder,
            VulkanFrameCommandPool commandPool,
            VkCommandBuffer commandBuffer
    ) {
        this.encoder = encoder;
        this.commandPool = commandPool;
        this.commandBuffer = commandBuffer;
    }

    public static VulkanFrameComputeBatch begin(
            VulkanDevice device,
            VulkanCapabilities capabilities,
            VulkanFrameCommandPool commandPool
    ) {
        if (!capabilities.canUseMinecraftFrameSubmissionForCompute()) {
            throw new IllegalStateException(
                    "Minecraft graphics queue does not advertise VK_QUEUE_COMPUTE_BIT"
            );
        }

        VulkanCommandEncoder encoder = device.createCommandEncoder();
        VkCommandBuffer commandBuffer = commandPool.acquire();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            int result = VK10.vkBeginCommandBuffer(commandBuffer, beginInfo);
            if (result != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkBeginCommandBuffer failed with VkResult " + result);
            }
        }

        return new VulkanFrameComputeBatch(encoder, commandPool, commandBuffer);
    }

    public VkCommandBuffer commandBuffer() {
        if (finished) {
            throw new IllegalStateException("Compute batch is already finished");
        }
        return commandBuffer;
    }

    public void finishAndEnqueue() {
        if (finished) {
            throw new IllegalStateException("Compute batch is already finished");
        }

        int result = VK10.vkEndCommandBuffer(commandBuffer);
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer failed with VkResult " + result);
        }
        encoder.execute(commandBuffer);
        commandPool.recycleAfterFence(commandBuffer);
        finished = true;
    }

    public boolean isFinished() {
        return finished;
    }
}
