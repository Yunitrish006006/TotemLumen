package dev.totem.lumen.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.totem.lumen.vulkan.VulkanDeviceLifetime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VulkanDevice.class, remap = false)
public abstract class VulkanDeviceLifetimeMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void totemLumen$awaitNativeWorkers(CallbackInfo ci) {
        VulkanDeviceLifetime.beforeDeviceClose((VulkanDevice) (Object) this);
    }
}
