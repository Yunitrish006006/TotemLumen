package dev.totem.lumen.render;

import com.mojang.blaze3d.systems.RenderSystem;

import java.util.Locale;

public final class BackendProbe {
    private BackendProbe() {
    }

    public static BackendStatus detect() {
        var device = RenderSystem.tryGetDevice();
        if (device == null) {
            return BackendStatus.waitingForDevice();
        }

        var info = device.getDeviceInfo();
        String backendName = String.valueOf(info.backendName());
        boolean vulkan = backendName.toLowerCase(Locale.ROOT).contains("vulkan");

        return new BackendStatus(
                true,
                vulkan,
                backendName,
                String.valueOf(info.name()),
                String.valueOf(info.vendorName()),
                String.valueOf(info.driverInfo())
        );
    }
}
