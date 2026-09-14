package dev.totem.lumen.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

/** Queries only core physical-device limits needed by the compute baseline. */
public final class VulkanCapabilityProbe {
    private VulkanCapabilityProbe() {
    }

    public static VulkanCapabilities probe(VulkanDevice device, VulkanBackendInfo backendInfo) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceProperties(device.vkDevice().getPhysicalDevice(), properties);
            VkPhysicalDeviceLimits limits = properties.limits();

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
                    backendInfo.hasDeviceExtension("VK_KHR_acceleration_structure"),
                    backendInfo.hasDeviceExtension("VK_KHR_ray_tracing_pipeline"),
                    backendInfo.hasDeviceExtension("VK_KHR_ray_query")
            );
        }
    }
}
