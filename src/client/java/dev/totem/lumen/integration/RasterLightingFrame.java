package dev.totem.lumen.integration;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import java.util.Objects;

/** Frame-local INDIRECT_GI output consumed by COMPOSITE. The producing stage owns the view. */
public record RasterLightingFrame(
        GpuDevice device,
        int width,
        int height,
        long surfaceFrameSerial,
        long voxelEpoch,
        boolean completeScene,
        GpuTextureView radiance
) {
    public RasterLightingFrame {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(radiance, "radiance");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Non-positive lighting extent");
        if (surfaceFrameSerial <= 0) throw new IllegalArgumentException("Invalid surface frame serial");
        if (voxelEpoch < 0) throw new IllegalArgumentException("Invalid voxel epoch");
    }

    public boolean matches(RasterSurfaceFrame surface) {
        return surface != null && surface.device() == device && surface.frameSerial() == surfaceFrameSerial;
    }
}
