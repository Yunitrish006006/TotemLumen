package dev.totem.lumen.vulkan;

import java.util.Locale;
import java.util.Set;

/**
 * Immutable diagnostics/capability snapshot. Native Vulkan handles are intentionally not exposed
 * from this record so ordinary renderer code cannot accidentally assume ownership of them.
 */
public record VulkanBackendInfo(
        String deviceName,
        String vendorName,
        String driverInfo,
        int graphicsQueueFamily,
        int computeQueueFamily,
        boolean minecraftVmaAvailable,
        Set<String> underlyingExtensions
) {
    public VulkanBackendInfo {
        underlyingExtensions = Set.copyOf(underlyingExtensions);
    }

    public boolean separateComputeQueueFamily() {
        return computeQueueFamily != graphicsQueueFamily;
    }

    /**
     * Diagnostic hint only. Feature paths must still be selected from actual Vulkan capabilities,
     * never from this platform guess.
     */
    public boolean likelyMoltenVk() {
        String combined = (vendorName + " " + driverInfo + " " + String.join(" ", underlyingExtensions))
                .toLowerCase(Locale.ROOT);
        return combined.contains("moltenvk")
                || combined.contains("apple")
                || combined.contains("portability_subset");
    }
}
