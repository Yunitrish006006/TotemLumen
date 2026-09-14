package dev.totem.lumen.render;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.platform.PlatformProfile;

public final class RendererBootstrap {
    private static RendererState state = RendererState.NEW;
    private static PlatformProfile platformProfile;

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

        state = RendererState.READY_FOR_SCENE_EXTRACTION;
        TotemLumenClient.LOGGER.info("Vulkan backend accepted; P0 bootstrap complete and renderer is ready for scene extraction work");
    }

    public static RendererState state() {
        return state;
    }

    public static PlatformProfile platformProfile() {
        return platformProfile;
    }
}
