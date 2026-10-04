package dev.totem.lumen.integration;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import java.util.Objects;

/**
 * Frame-local DIRECT_LIGHT output.
 *
 * <p>RGB stores direct illumination only; alpha is material coverage. This frame is diagnostic
 * until COMPOSITE explicitly consumes it.</p>
 */
record RasterDirectLightFrame(
        GpuDevice device,
        int width,
        int height,
        long surfaceFrameSerial,
        int selectedLights,
        GpuTextureView radiance
) {
    RasterDirectLightFrame {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(radiance, "radiance");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Non-positive direct-light extent");
        if (surfaceFrameSerial <= 0) throw new IllegalArgumentException("Invalid surface frame serial");
        if (selectedLights < 0 || selectedLights > 32) throw new IllegalArgumentException("Invalid selected-light count");
    }

    boolean matches(RasterSurfaceFrame surface, RasterMaterialFrame material) {
        return surface != null
                && material != null
                && material.matches(surface)
                && device == surface.device()
                && width == surface.width()
                && height == surface.height()
                && surfaceFrameSerial == surface.frameSerial();
    }
}
