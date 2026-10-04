package dev.totem.lumen.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Hands background retirements to Minecraft's render-thread-owned Vulkan destruction queue. */
public final class VulkanDeferredDestruction {
    private static final ConcurrentLinkedQueue<Runnable> PENDING = new ConcurrentLinkedQueue<>();

    private VulkanDeferredDestruction() {}

    static void retire(VulkanDevice device, Runnable destroy) {
        Runnable enqueue = () -> device.createCommandEncoder().queueForDestroy(destroy::run);
        if (RenderSystem.isOnRenderThread()) {
            enqueue.run();
        } else {
            PENDING.add(enqueue);
        }
    }

    /** Call every client tick, including menus/other profiles, and before renderer shutdown. */
    public static void drain() {
        RenderSystem.assertOnRenderThread();
        Runnable enqueue;
        while ((enqueue = PENDING.poll()) != null) enqueue.run();
    }
}
