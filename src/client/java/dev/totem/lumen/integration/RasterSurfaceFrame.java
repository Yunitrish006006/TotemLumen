package dev.totem.lumen.integration;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import java.util.Objects;

/**
 * Borrowed, frame-local output of the raster SURFACE_CAPTURE stage.
 *
 * <p>The capture stage owns the texture views. Downstream stages may sample them only for the
 * matching device/extent and must never close or retain them across frames.</p>
 */
public record RasterSurfaceFrame(
        GpuDevice device,
        int width,
        int height,
        long frameSerial,
        ColorSemantic colorSemantic,
        GpuTextureView baseColor,
        GpuTextureView depth,
        GpuTextureView normal
) {
    public enum ColorSemantic {
        /** Minecraft's completed native color; safe surface source, but already visually lit. */
        NATIVE_LIT_COLOR,
        /** Future material stage output that can be lit independently without vanilla lightmap. */
        UNLIT_MATERIAL_COLOR
    }

    public RasterSurfaceFrame {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(colorSemantic, "colorSemantic");
        Objects.requireNonNull(baseColor, "baseColor");
        Objects.requireNonNull(depth, "depth");
        Objects.requireNonNull(normal, "normal");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Non-positive surface extent");
        if (frameSerial <= 0) throw new IllegalArgumentException("Invalid frame serial");
    }

    public boolean matches(GpuDevice gpu, int expectedWidth, int expectedHeight) {
        return device == gpu && width == expectedWidth && height == expectedHeight;
    }

    public boolean supportsIndependentLighting() {
        return colorSemantic == ColorSemantic.UNLIT_MATERIAL_COLOR;
    }
}
