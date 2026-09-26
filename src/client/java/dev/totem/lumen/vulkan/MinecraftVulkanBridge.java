package dev.totem.lumen.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.totem.lumen.mixin.GpuDeviceAccessor;
import org.jspecify.annotations.Nullable;

/**
 * Access point for Minecraft 26.3's Vulkan backend.
 *
 * <p>Minecraft owns the instance, device, allocator, queues, swapchain and shutdown order. Totem
 * Lumen may borrow the backend for compute/storage work that public Blaze3D does not currently
 * expose, but must never close/destroy Minecraft-owned objects.</p>
 */
public final class MinecraftVulkanBridge {
    private MinecraftVulkanBridge() {
    }

    public static @Nullable VulkanDevice currentDevice() {
        var gpuDevice = RenderSystem.tryGetDevice();
        if (gpuDevice == null) {
            return null;
        }

        var backend = ((GpuDeviceAccessor) gpuDevice).totemLumen$getBackend();
        return backend instanceof VulkanDevice vulkanDevice ? vulkanDevice : null;
    }

    public static @Nullable VulkanBackendInfo inspect() {
        VulkanDevice device = currentDevice();
        if (device == null) {
            return null;
        }

        var info = device.getDeviceInfo();
        return new VulkanBackendInfo(
                info.name(),
                info.vendorName(),
                info.driverInfo(),
                device.graphicsQueue().queueFamilyIndex(),
                device.computeQueue().queueFamilyIndex(),
                device.vma() != 0L,
                info.underlyingExtensions()
        );
    }
}
