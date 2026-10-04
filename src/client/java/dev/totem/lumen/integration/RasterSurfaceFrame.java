package dev.totem.lumen.integration;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import java.util.Objects;

/**
 * Borrowed, frame-local output of the raster surface-capture stage.
 *
 * <p>The capture stage owns the texture views. Downstream stages may sample them only for the
 * matching frame/device/extent and must never close or retain them across frames.</p>
 */
public record RasterSurfaceFrame(
        GpuDevice device,
        int width,
        int height,
        long frameSerial,
        GpuTextureView baseColor,
        GpuTextureView depth
) {
    public RasterSurfaceFrame {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(baseColor, "baseColor");
        Objects.requireNonNull(depth, "depth");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Non-positive surface extent");
        if (frameSerial <= 0) throw new IllegalArgumentException("Invalid frame serial");
    }

    public boolean matches(GpuDevice gpu, int expectedWidth, int expectedHeight, long expectedFrameSerial) {
        return device == gpu
                && width == expectedWidth
                && height == expectedHeight
                && frameSerial == expectedFrameSerial;
    }
}
