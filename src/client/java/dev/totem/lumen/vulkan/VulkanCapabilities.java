package dev.totem.lumen.vulkan;

/** Core Vulkan limits used to size and select the portable compute path. */
public record VulkanCapabilities(
        int apiVersion,
        long maxStorageBufferRange,
        int maxComputeWorkGroupInvocations,
        int maxComputeWorkGroupSizeX,
        int maxComputeWorkGroupSizeY,
        int maxComputeWorkGroupSizeZ,
        int maxComputeWorkGroupCountX,
        int maxComputeWorkGroupCountY,
        int maxComputeWorkGroupCountZ,
        boolean timestampComputeAndGraphics,
        boolean graphicsQueueSupportsCompute,
        boolean computeQueueSupportsCompute,
        boolean accelerationStructureExtensionEnabled,
        boolean rayTracingPipelineExtensionEnabled,
        boolean rayQueryExtensionEnabled
) {
    public boolean baselineComputeUsable() {
        return maxStorageBufferRange >= 16L * 1024L
                && maxComputeWorkGroupInvocations >= 64
                && maxComputeWorkGroupSizeX >= 8
                && maxComputeWorkGroupSizeY >= 8
                && (graphicsQueueSupportsCompute || computeQueueSupportsCompute);
    }

    /**
     * Preferred P3/P4 path: record compute commands into Minecraft's existing graphics submission.
     * This preserves Minecraft's frame synchronization and avoids an extra queue ownership protocol.
     */
    public boolean canUseMinecraftFrameSubmissionForCompute() {
        return graphicsQueueSupportsCompute;
    }

    public boolean hardwareRayTracingExtensionsEnabled() {
        return accelerationStructureExtensionEnabled && rayTracingPipelineExtensionEnabled;
    }
}
