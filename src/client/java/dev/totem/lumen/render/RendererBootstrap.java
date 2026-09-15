package dev.totem.lumen.render;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.platform.PlatformProfile;
import dev.totem.lumen.vulkan.MinecraftVulkanBridge;
import dev.totem.lumen.vulkan.VulkanBackendInfo;
import dev.totem.lumen.vulkan.VulkanCapabilities;
import dev.totem.lumen.vulkan.VulkanCapabilityProbe;
import dev.totem.lumen.vulkan.VulkanComputeProgram;

public final class RendererBootstrap {
    private static RendererState state = RendererState.NEW;
    private static PlatformProfile platformProfile;
    private static VulkanBackendInfo vulkanBackendInfo;
    private static VulkanCapabilities vulkanCapabilities;

    private RendererBootstrap() {
    }

    public static void initialize() {
        if (state != RendererState.NEW) {
            return;
        }

        platformProfile = PlatformProfile.detect();
        TotemLumenClient.LOGGER.info("Platform: {}", platformProfile.displayName());

        if (platformProfile.appleSilicon()) {
            TotemLumenClient.LOGGER.info(
                    "Apple Silicon detected; Totem Lumen will use Minecraft's Vulkan backend through the MoltenVK/Metal path"
            );
        }

        state = RendererState.WAITING_FOR_DEVICE;
    }

    public static void tick() {
        if (state != RendererState.WAITING_FOR_DEVICE) {
            return;
        }

        BackendStatus status = BackendProbe.detect();
        if (!status.deviceAvailable()) {
            return;
        }

        TotemLumenClient.LOGGER.info(
                "Graphics backend: {} | GPU: {} | Vendor: {} | Driver: {}",
                status.backendName(),
                status.deviceName(),
                status.vendorName(),
                status.driverInfo()
        );

        if (!status.vulkan()) {
            state = RendererState.DISABLED_NON_VULKAN;
            TotemLumenClient.LOGGER.error(
                    "Totem Lumen requires Minecraft's Vulkan backend. Current backend: {}. No OpenGL fallback will be provided.",
                    status.backendName()
            );
            return;
        }

        var vulkanDevice = MinecraftVulkanBridge.currentDevice();
        vulkanBackendInfo = MinecraftVulkanBridge.inspect();
        if (vulkanDevice == null || vulkanBackendInfo == null) {
            TotemLumenClient.LOGGER.error(
                    "Minecraft reports Vulkan, but Totem Lumen could not access the Vulkan backend. GPU rendering will remain unavailable."
            );
            state = RendererState.READY_FOR_SCENE_EXTRACTION;
            return;
        }

        vulkanCapabilities = VulkanCapabilityProbe.probe(vulkanDevice, vulkanBackendInfo);
        TotemLumenClient.LOGGER.info(
                "Vulkan interop: graphicsQueueFamily={} (compute={}), computeQueueFamily={} (compute={}), separateComputeFamily={}, MoltenVKLikely={}",
                vulkanBackendInfo.graphicsQueueFamily(),
                vulkanCapabilities.graphicsQueueSupportsCompute(),
                vulkanBackendInfo.computeQueueFamily(),
                vulkanCapabilities.computeQueueSupportsCompute(),
                vulkanBackendInfo.separateComputeQueueFamily(),
                vulkanBackendInfo.likelyMoltenVk()
        );
        TotemLumenClient.LOGGER.info(
                "Vulkan compute limits: maxStorageBufferRange={} MiB, maxInvocations={}, workGroupSize={}x{}x{}",
                vulkanCapabilities.maxStorageBufferRange() / (1024L * 1024L),
                vulkanCapabilities.maxComputeWorkGroupInvocations(),
                vulkanCapabilities.maxComputeWorkGroupSizeX(),
                vulkanCapabilities.maxComputeWorkGroupSizeY(),
                vulkanCapabilities.maxComputeWorkGroupSizeZ()
        );

        if (!vulkanCapabilities.baselineComputeUsable()) {
            TotemLumenClient.LOGGER.error(
                    "The active Vulkan device does not meet Totem Lumen's minimum compute/storage limits; GPU RT will remain unavailable."
            );
        } else if (vulkanCapabilities.canUseMinecraftFrameSubmissionForCompute()) {
            TotemLumenClient.LOGGER.info(
                    "Compute integration mode: Minecraft graphics submission (preferred portable path)"
            );
            // Pipeline compilation can be very expensive on MoltenVK because it translates SPIR-V
            // to MSL and invokes the Metal compiler. Start it as soon as the Vulkan device is known,
            // while resource loading is still in progress, and never make world rendering wait.
            VulkanComputeProgram.prewarmMainGiPipeline(vulkanDevice);
        } else {
            TotemLumenClient.LOGGER.warn(
                    "Graphics queue lacks compute support; a dedicated compute-queue synchronization path will be required on this device"
            );
        }

        if (vulkanCapabilities.hardwareRayTracingExtensionsEnabled()) {
            TotemLumenClient.LOGGER.info("Optional Vulkan hardware RT extensions are already enabled on the Minecraft device");
        } else {
            TotemLumenClient.LOGGER.info("Hardware RT extensions are not required; Vulkan compute voxel RT remains the baseline path");
        }

        state = RendererState.READY_FOR_SCENE_EXTRACTION;
        TotemLumenClient.LOGGER.info("Vulkan backend accepted; CPU scene extraction and P3 Vulkan interop are ready");
    }

    public static RendererState state() {
        return state;
    }

    public static PlatformProfile platformProfile() {
        return platformProfile;
    }

    public static VulkanBackendInfo vulkanBackendInfo() {
        return vulkanBackendInfo;
    }

    public static VulkanCapabilities vulkanCapabilities() {
        return vulkanCapabilities;
    }
}
