package dev.totem.lumen.integration;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.totem.lumen.render.RasterLightingVolume;

import java.util.Objects;

/** Frame-local shared GPU representation of the staged raster VOXEL_SCENE input. */
record RasterVoxelSceneFrame(
        GpuDevice device,
        long epoch,
        boolean coherent,
        boolean completeScene,
        GpuTextureView voxelAtlas
) {
    RasterVoxelSceneFrame {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(voxelAtlas, "voxelAtlas");
    }

    boolean matches(GpuDevice gpu, RasterLightingVolume volume) {
        return volume != null && device == gpu && epoch == volume.epoch;
    }
}
