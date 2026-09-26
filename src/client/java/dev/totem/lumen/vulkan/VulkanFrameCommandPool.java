package dev.totem.lumen.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;

import java.nio.LongBuffer;
import java.util.ArrayDeque;

/** Totem-Lumen-owned graphics-family command pool whose submissions stay on Minecraft's timeline. */
public final class VulkanFrameCommandPool implements AutoCloseable {
    private final VulkanDevice device;
    private final long commandPool;
    private final ArrayDeque<VkCommandBuffer> available = new ArrayDeque<>();
    private int inFlight;
    private boolean closed;

    public VulkanFrameCommandPool(VulkanDevice device) {
        this.device = device;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo createInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(device.graphicsQueue().queueFamilyIndex());
            LongBuffer handle = stack.mallocLong(1);
            int result = VK10.vkCreateCommandPool(device.vkDevice(), createInfo, null, handle);
            if (result != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkCreateCommandPool failed with VkResult " + result);
            }
            this.commandPool = handle.get(0);
        }
    }

    public synchronized VkCommandBuffer acquire() {
        ensureOpen();
        VkCommandBuffer existing = available.pollFirst();
        if (existing != null) {
            return existing;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo allocateInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(commandPool)
                    .level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pointer = stack.mallocPointer(1);
            int result = VK10.vkAllocateCommandBuffers(device.vkDevice(), allocateInfo, pointer);
            if (result != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkAllocateCommandBuffers failed with VkResult " + result);
            }
            return new VkCommandBuffer(pointer.get(0), device.vkDevice());
        }
    }

    public void recycleAfterFence(VkCommandBuffer commandBuffer) {
        recycleAfterFence(commandBuffer, () -> { });
    }

    public synchronized void recycleAfterFence(VkCommandBuffer commandBuffer, Runnable afterRecycle) {
        ensureOpen();
        inFlight++;
        RenderSystem.queueFencedTask(() -> {
            recycleCompleted(commandBuffer);
            afterRecycle.run();
        });
    }

    private synchronized void recycleCompleted(VkCommandBuffer commandBuffer) {
        inFlight--;
        if (closed) {
            return;
        }
        int result = VK10.vkResetCommandBuffer(commandBuffer, 0);
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkResetCommandBuffer failed with VkResult " + result);
        }
        available.addLast(commandBuffer);
    }

    public synchronized int inFlightCount() {
        return inFlight;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        if (inFlight != 0) {
            throw new IllegalStateException("Cannot destroy command pool with in-flight command buffers: " + inFlight);
        }
        closed = true;
        available.clear();
        VK10.vkDestroyCommandPool(device.vkDevice(), commandPool, null);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Vulkan command pool is already closed");
        }
    }
}
