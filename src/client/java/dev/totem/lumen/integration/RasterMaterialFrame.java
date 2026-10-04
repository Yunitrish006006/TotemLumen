package dev.totem.lumen.integration;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import java.util.Objects;

/**
 * Frame-local MATERIAL_RESOLVE output.
 *
 * <p>The visible-surface texture stores exact 16-bit session material ID in R/G and exact
 * 16-bit P18 surface-set ID in B/A. The LUT stores each 32-bit MaterialDefinition ABI word
 * losslessly across one RGBA8 texel;
 * consumers reconstruct raw words instead of accepting an 8-bit float quantization.</p>
 */
public record RasterMaterialFrame(
        GpuDevice device,
        int width,
        int height,
        long surfaceFrameSerial,
        long materialRevision,
        int materialCount,
        long surfaceSetRevision,
        GpuTextureView visibleSurfaceIdentity,
        GpuTextureView baseProperties,
        GpuTextureView materialLut,
        GpuTextureView surfaceSetLut
) {
    public RasterMaterialFrame {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(visibleSurfaceIdentity, "visibleSurfaceIdentity");
        Objects.requireNonNull(baseProperties, "baseProperties");
        Objects.requireNonNull(materialLut, "materialLut");
        Objects.requireNonNull(surfaceSetLut, "surfaceSetLut");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Non-positive material extent");
        if (surfaceFrameSerial <= 0) throw new IllegalArgumentException("Invalid surface frame serial");
        if (materialRevision < 0 || materialCount < 0 || surfaceSetRevision < 0)
            throw new IllegalArgumentException("Invalid material snapshot metadata");
    }

    public boolean matches(RasterSurfaceFrame surface) {
        return surface != null
                && surface.device() == device
                && surface.width() == width
                && surface.height() == height
                && surface.frameSerial() == surfaceFrameSerial;
    }
}
