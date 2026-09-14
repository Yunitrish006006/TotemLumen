package dev.totem.lumen.vulkan;

/** Core Vulkan limits used to size the portable compute path. */
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
        boolean accelerationStructureExtensionEnabled,
        boolean rayTracingPipelineExtensionEnabled,
        boolean rayQueryExtensionEnabled
) {
    public boolean baselineComputeUsable() {
        return maxStorageBufferRange >= 16L * 1024L
                && maxComputeWorkGroupInvocations >= 64
                && maxComputeWorkGroupSizeX >= 8
                && maxComputeWorkGroupSizeY >= 8;
    }

    public boolean hardwareRayTracingExtensionsEnabled() {
        return accelerationStructureExtensionEnabled && rayTracingPipelineExtensionEnabled;
    }
}
