package dev.totem.lumen.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.totem.lumen.vulkan.resource.VulkanOwnedBuffer;

/**
 * Compatibility facade for the former separate dynamic-entity enhanced pipeline.
 *
 * <p>Dynamic-entity nearest-hit tracing now lives directly in the single full-lighting pipeline.
 * Keeping this facade avoids churn in command-recording mixins and progress/status code while
 * eliminating a second 100k+ character MoltenVK/Metal pipeline compilation on cold start.</p>
 */
public final class P17EnhancedBasePipeline {
    /**
     * Retained only for diagnostics/backward compatibility. No runtime pipeline is created with
     * this shader name anymore.
     */
    public static final String SHADER_NAME = "totem_lumen_p17_dynamic_entities.comp";
    public static final String WORKER_NAME = "TotemLumen-P17Pipeline";

    private P17EnhancedBasePipeline() {
    }

    public static void prewarm(VulkanDevice device) {
        // Entity tracing is already part of P12FullBasePipeline.
    }

    public static void attach(VulkanDevice device, VulkanOwnedBuffer scene) {
        // Entity tracing uses the full-lighting descriptor binding.
    }

    static String buildSourceForVerification() {
        return P12FullBasePipeline.buildSourceForVerification();
    }

    public static void beginDispatch() {
        P12FullBasePipeline.beginDispatch();
    }

    public static VulkanComputeProgram selectForCurrentDispatch(VulkanComputeProgram bootstrap) {
        return P12FullBasePipeline.selectForCurrentDispatch(bootstrap);
    }

    public static void endDispatch() {
        P12FullBasePipeline.endDispatch();
    }

    public static boolean ready() {
        return P12FullBasePipeline.ready();
    }

    public static Throwable failure() {
        return P12FullBasePipeline.failure();
    }

    public static void shutdown() {
        // Owned by P12FullBasePipeline.
    }
}
