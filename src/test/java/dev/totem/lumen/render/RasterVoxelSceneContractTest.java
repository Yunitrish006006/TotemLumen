package dev.totem.lumen.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RasterVoxelSceneContractTest {
    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void sharedVoxelSceneOwnsAtlasAndUploadsOncePerCoordinatorFrame() throws Exception {
        String shared = read("src/client/java/dev/totem/lumen/integration/RasterVoxelSceneGpu.java");
        String renderer = read("src/client/java/dev/totem/lumen/integration/RasterLightingRenderer.java");
        String gi = read("src/client/java/dev/totem/lumen/integration/RasterIndirectGiStage.java");

        assertTrue(shared.contains("Raster shared voxel scene atlas"));
        assertTrue(shared.contains("RasterLightingWindow.UPLOADS_PER_FRAME"));
        assertTrue(shared.contains("return new RasterVoxelSceneFrame"));
        assertTrue(renderer.contains("RasterVoxelSceneGpu.prepare(encoder, gpu, volume)"));
        assertTrue(renderer.contains("if (!voxelScene.coherent())"));
        assertTrue(renderer.contains("surface, voxelScene, uniforms, nearest"));
        assertTrue(renderer.contains("RasterVoxelSceneGpu.close()"));

        assertFalse(gi.contains("Raster INDIRECT_GI voxel atlas"));
        assertFalse(gi.contains("NativeImage"));
        assertFalse(gi.contains("RasterLightingWindow.UPLOADS_PER_FRAME"));
        assertTrue(gi.contains("voxelScene.voxelAtlas()"));
    }

    @Test
    void indirectGiRejectsIncoherentSharedScene() throws Exception {
        String gi = read("src/client/java/dev/totem/lumen/integration/RasterIndirectGiStage.java");
        assertTrue(gi.contains("if (voxelScene == null || !voxelScene.coherent())"));
        assertTrue(gi.contains("INDIRECT_GI requires a coherent shared VOXEL_SCENE"));
    }
}
