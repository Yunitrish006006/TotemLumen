package dev.totem.lumen.mixin;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Single narrow seam into Minecraft's already-created graphics backend.
 *
 * <p>Totem Lumen never creates a second VkInstance/VkDevice. Vulkan-native compute code obtains
 * the existing backend through this accessor and leaves device/swapchain ownership to Minecraft.</p>
 */
@Mixin(FrontendGpuDevice.class)
public interface GpuDeviceAccessor {
    @Accessor("backend")
    GpuDeviceBackend totemLumen$getBackend();
}
