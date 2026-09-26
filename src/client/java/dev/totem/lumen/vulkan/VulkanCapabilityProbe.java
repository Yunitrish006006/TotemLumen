package dev.totem.lumen.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.IntBuffer;

/** Queries core physical-device limits and queue properties needed by the compute baseline. */
public final class VulkanCapabilityProbe {
    private VulkanCapabilityProbe() {
    }

    public static VulkanCapabilities probe(VulkanDevice device, VulkanBackendInfo backendInfo) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceProperties(device.vkDevice().getPhysicalDevice(), properties);
            VkPhysicalDeviceLimits limits = properties.limits();

            boolean graphicsSupportsCompute = queueFamilySupportsCompute(
                    stack, device, backendInfo.graphicsQueueFamily()
            );
            boolean computeSupportsCompute = queueFamilySupportsCompute(
                    stack, device, backendInfo.computeQueueFamily()
            );

            return new VulkanCapabilities(
                    properties.apiVersion(),
                    Integer.toUnsignedLong(limits.maxStorageBufferRange()),
                    limits.maxComputeWorkGroupInvocations(),
                    limits.maxComputeWorkGroupSize(0),
                    limits.maxComputeWorkGroupSize(1),
                    limits.maxComputeWorkGroupSize(2),
                    limits.maxComputeWorkGroupCount(0),
                    limits.maxComputeWorkGroupCount(1),
                    limits.maxComputeWorkGroupCount(2),
                    limits.timestampComputeAndGraphics(),
                    graphicsSupportsCompute,
                    computeSupportsCompute,
                    backendInfo.hasDeviceExtension("VK_KHR_acceleration_structure"),
                    backendInfo.hasDeviceExtension("VK_KHR_ray_tracing_pipeline"),
                    backendInfo.hasDeviceExtension("VK_KHR_ray_query")
            );
        }
    }

    private static boolean queueFamilySupportsCompute(
            MemoryStack stack,
            VulkanDevice device,
            int queueFamilyIndex
    ) {
        IntBuffer count = stack.mallocInt(1);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(device.vkDevice().getPhysicalDevice(), count, null);
        int familyCount = count.get(0);
        if (queueFamilyIndex < 0 || queueFamilyIndex >= familyCount) {
            return false;
        }

        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(familyCount, stack);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(device.vkDevice().getPhysicalDevice(), count, families);
        return (families.get(queueFamilyIndex).queueFlags() & VK10.VK_QUEUE_COMPUTE_BIT) != 0;
    }
}
