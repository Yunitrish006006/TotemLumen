package dev.totem.lumen.integration;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.render.RasterLightingVolume;
import dev.totem.lumen.render.RasterLightingWindow;
import org.joml.Vector4f;

/**
 * Shared GPU ownership for the raster VOXEL_SCENE.
 *
 * <p>Lighting stages consume the same immutable atlas generation instead of allocating/uploading
 * their own copies. CPU section snapshots remain owned by RasterLightingScene.</p>
 */
final class RasterVoxelSceneGpu {
    private static GpuDevice device;
    private static GpuTexture atlas;
    private static GpuTextureView atlasView;
    private static NativeImage tile;
    private static final RasterLightingVolume.Section[] uploaded =
            new RasterLightingVolume.Section[RasterLightingVolume.SLOTS];
    private static long epoch = -1, uploadBytes;
    private static boolean logged;

    private RasterVoxelSceneGpu() { }

    static RasterVoxelSceneFrame prepare(
            CommandEncoder encoder,
            GpuDevice gpu,
            RasterLightingVolume volume
    ) {
        ensure(gpu);

        if (epoch != volume.epoch) {
            java.util.Arrays.fill(uploaded, null);
            epoch = volume.epoch;
            // Zero is unknown. Never retain old-world/stale occluders after a rebase.
            encoder.clearColorTexture(atlas, new Vector4f());
        }

        boolean complete = true;
        boolean pendingUploads = false;
        int uploadsThisFrame = 0;
        for (int slot = 0; slot < uploaded.length; slot++) {
            RasterLightingVolume.Section section = volume.section(slot);
            if (section == null) complete = false;
            if (section == uploaded[slot]) continue;
            if (uploadsThisFrame >= RasterLightingWindow.UPLOADS_PER_FRAME) {
                pendingUploads = true;
                continue;
            }

            for (int i = 0; i < 4096; i++) {
                tile.setPixelABGR(i & 15, i >>> 4, section == null ? 0 : section.voxel(i));
            }
            encoder.writeToTexture(
                    atlas,
                    tile,
                    0, 0,
                    RasterLightingVolume.tileX(slot),
                    RasterLightingVolume.tileY(slot));
            uploaded[slot] = section;
            uploadBytes += 16384;
            uploadsThisFrame++;
        }

        boolean coherent = !pendingUploads;
        if (coherent && !logged) {
            logged = true;
            TotemLumenClient.LOGGER.info(
                    "RASTER VOXEL_SCENE GPU input READY: atlas={}x{}, bytes={}, completeScene={}",
                    RasterLightingVolume.ATLAS_WIDTH,
                    RasterLightingVolume.ATLAS_HEIGHT,
                    RasterLightingVolume.ATLAS_WIDTH * RasterLightingVolume.ATLAS_HEIGHT * 4,
                    complete
            );
        }
        return new RasterVoxelSceneFrame(gpu, epoch, coherent, complete, atlasView);
    }

    private static void ensure(GpuDevice gpu) {
        if (device == gpu && atlas != null) return;
        close();
        device = gpu;
        atlas = gpu.createTexture(
                "Raster shared voxel scene atlas",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING
                        | GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.RGBA8_UNORM,
                RasterLightingVolume.ATLAS_WIDTH,
                RasterLightingVolume.ATLAS_HEIGHT,
                1, 1);
        atlasView = gpu.createTextureView(atlas);
        tile = new NativeImage(16, 256, false);
    }

    static void close() {
        if (atlasView != null) atlasView.close();
        if (atlas != null) atlas.close();
        if (tile != null) tile.close();
        atlasView = null;
        atlas = null;
        tile = null;
        device = null;
        java.util.Arrays.fill(uploaded, null);
        epoch = -1;
        if (logged) {
            TotemLumenClient.LOGGER.info(
                    "Raster VOXEL_SCENE GPU input retired: uploadBytes={}", uploadBytes);
        }
        uploadBytes = 0;
        logged = false;
    }
}
