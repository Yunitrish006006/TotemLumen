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
    private static boolean loggedInteropWait;

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
        TotemLumenClient.LOGGER.info("Renderer state: WAITING_FOR_DEVICE");
    }

    public static void tick() {
        switch (state) {
            case WAITING_FOR_DEVICE -> detectGraphicsBackend();
            case WAITING_FOR_VULKAN_INTEROP -> tryInitializeVulkanInterop();
            case WAITING_FOR_PIPELINE -> pollPipelinePrewarm();
            default -> {
            }
        }
    }

    private static void detectGraphicsBackend() {
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
                    "Renderer state: DISABLED_NON_VULKAN. Totem Lumen requires Minecraft's Vulkan backend. Current backend: {}. No OpenGL fallback will be provided.",
                    status.backendName()
            );
            return;
        }

        state = RendererState.WAITING_FOR_VULKAN_INTEROP;
        TotemLumenClient.LOGGER.info("Renderer state: WAITING_FOR_VULKAN_INTEROP");
        tryInitializeVulkanInterop();
    }

    private static void tryInitializeVulkanInterop() {
        var vulkanDevice = MinecraftVulkanBridge.currentDevice();
        vulkanBackendInfo = MinecraftVulkanBridge.inspect();
        if (vulkanDevice == null || vulkanBackendInfo == null) {
            if (!loggedInteropWait) {
                loggedInteropWait = true;
                TotemLumenClient.LOGGER.warn(
                        "Minecraft reports Vulkan, but its Vulkan backend is not accessible to Totem Lumen yet; staying in WAITING_FOR_VULKAN_INTEROP and retrying on later client ticks"
                );
            }
            return;
        }
        loggedInteropWait = false;

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
            state = RendererState.DISABLED_UNSUPPORTED_VULKAN;
            TotemLumenClient.LOGGER.error(
                    "Renderer state: DISABLED_UNSUPPORTED_VULKAN. The active Vulkan device does not meet Totem Lumen's minimum compute/storage limits."
            );
            return;
        }

        if (!vulkanCapabilities.canUseMinecraftFrameSubmissionForCompute()) {
            state = RendererState.DISABLED_UNSUPPORTED_VULKAN;
            TotemLumenClient.LOGGER.error(
                    "Renderer state: DISABLED_UNSUPPORTED_VULKAN. Minecraft's graphics queue lacks compute support and the dedicated compute-queue synchronization path is not implemented yet."
            );
            return;
        }

        TotemLumenClient.LOGGER.info(
                "Compute integration mode: Minecraft graphics submission (preferred portable path)"
        );

        if (vulkanCapabilities.hardwareRayTracingExtensionsEnabled()) {
            TotemLumenClient.LOGGER.info("Optional Vulkan hardware RT extensions are already enabled on the Minecraft device");
        } else {
            TotemLumenClient.LOGGER.info("Hardware RT extensions are not required; Vulkan compute voxel RT remains the baseline path");
        }

        // Pipeline compilation can be very expensive on MoltenVK because it translates SPIR-V
        // to MSL and invokes the Metal compiler. Start it as soon as the Vulkan device is known,
        // while resource loading is still in progress, and never make world rendering wait.
        VulkanComputeProgram.prewarmMainGiPipeline(vulkanDevice);
        state = RendererState.WAITING_FOR_PIPELINE;
        TotemLumenClient.LOGGER.info(
                "Renderer state: WAITING_FOR_PIPELINE. Minecraft presentation remains active through bootstrap and full-base compilation; Totem Lumen composites only after a complete full-base frame."
        );
        pollPipelinePrewarm();
    }

    private static void pollPipelinePrewarm() {
        Throwable shaderFailure = VulkanComputeProgram.mainGiShaderPrewarmFailure();
        if (shaderFailure != null) {
            state = RendererState.PIPELINE_FAILED;
            TotemLumenClient.LOGGER.error(
                    "Renderer state: PIPELINE_FAILED. Totem Lumen shader prewarm failed; its renderer is disabled for this session while Minecraft continues.",
                    shaderFailure
            );
            return;
        }

        Throwable pipelineFailure = VulkanComputeProgram.mainGiPipelinePrewarmFailure();
        if (pipelineFailure != null) {
            state = RendererState.PIPELINE_FAILED;
            TotemLumenClient.LOGGER.error(
                    "Renderer state: PIPELINE_FAILED. Totem Lumen Vulkan pipeline prewarm failed; its renderer is disabled for this session while Minecraft continues.",
                    pipelineFailure
            );
            return;
        }

        if (!VulkanComputeProgram.mainGiShaderPrewarmReady()
                || !VulkanComputeProgram.mainGiPipelinePrewarmReady()) {
            return;
        }

        state = RendererState.READY_FOR_SCENE_EXTRACTION;
        TotemLumenClient.LOGGER.info(
                "Renderer state: READY_FOR_SCENE_EXTRACTION. Vulkan backend and background pipeline prewarm are ready."
        );
    }

    public static boolean readyForRendering() {
        return state == RendererState.READY_FOR_SCENE_EXTRACTION;
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
