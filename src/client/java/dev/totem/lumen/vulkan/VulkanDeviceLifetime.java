package dev.totem.lumen.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.vulkan.resource.VulkanWorkerLifetime;

import java.util.Map;
import java.util.WeakHashMap;

/** Pins Minecraft's borrowed device for the entire native worker, including failed/stale cleanup. */
public final class VulkanDeviceLifetime {
    private static final Map<VulkanDevice, VulkanWorkerLifetime> DEVICES = new WeakHashMap<>();

    private VulkanDeviceLifetime() {}

    private static synchronized VulkanWorkerLifetime lifetime(VulkanDevice device) {
        return DEVICES.computeIfAbsent(device, ignored -> new VulkanWorkerLifetime());
    }

    static void startWorker(VulkanDevice device, String name, Runnable work) {
        var lease = lifetime(device).tryAcquire();
        if (lease == null) return;
        try {
            Thread worker = new Thread(() -> {
                try (lease) {
                    work.run();
                }
            }, name);
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable failure) {
            lease.close();
            throw failure;
        }
    }

    /** Only final backend destruction waits; resizing, world changes and frame rendering do not. */
    public static void beforeDeviceClose(VulkanDevice device) {
        VulkanWorkerLifetime lifetime = lifetime(device);
        int active = lifetime.stopAccepting();
        // Cancel Java-side binding waits before joining their device leases.
        P12FullBasePipeline.shutdown();
        if (active != 0) {
            TotemLumenClient.LOGGER.info(
                    "Waiting for {} Vulkan worker(s) before device shutdown; native compilation cannot be interrupted safely",
                    active);
        }
        lifetime.awaitCompletion();
        VulkanComputeProgram.retireMainGiPipeline();
        VulkanDeferredDestruction.drain();
        VulkanComputeProgram.shutdownPipelineCaches();
        TotemLumenClient.LOGGER.info("Vulkan device workers quiesced; backend destruction is safe");
    }
}
