package dev.totem.lumen.render;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RasterLightingVolumeTest {
    @Test void atlasMapsEveryVoxelExactlyOnceWithinPortableDimensions() {
        boolean[] seen = new boolean[RasterLightingVolume.ATLAS_WIDTH * RasterLightingVolume.ATLAS_HEIGHT];
        for (int y = 0; y < 96; y++) for (int z = 0; z < 96; z++) for (int x = 0; x < 96; x++) {
            int slot = RasterLightingVolume.slot(x / 16, y / 16, z / 16);
            int tx = RasterLightingVolume.tileX(slot) + x % 16;
            int ty = RasterLightingVolume.tileY(slot) + (y % 16) * 16 + z % 16;
            int i = ty * RasterLightingVolume.ATLAS_WIDTH + tx;
            assertFalse(seen[i]); seen[i] = true;
        }
        assertTrue(RasterLightingVolume.ATLAS_HEIGHT <= 4096);
    }

    @Test void negativeCoordinatesAndMovingCameraRetainRayMargin() {
        for (double camera : new double[]{-30000000.1, -16.1, -0.1, 0, 15.99, 16, 30000000.1}) {
            int origin = RasterLightingVolume.origin(camera);
            assertEquals(0, Math.floorMod(origin, 16));
            assertTrue(camera - origin >= RasterLightingVolume.RAY_DISTANCE);
            assertTrue(origin + RasterLightingVolume.SIDE - camera >= RasterLightingVolume.RAY_DISTANCE);
        }
    }

    @Test void sectionMaterialIdsAreImmutableUnsigned16BitPayloads() {
        int[] pixels = new int[4096];
        char[] materials = new char[4096];
        materials[0] = (char) 1;
        materials[1] = (char) 0xFFFF;
        var section = new RasterLightingVolume.Section(pixels, materials);
        materials[0] = 99;
        assertEquals(1, section.materialId(0));
        assertEquals(65535, section.materialId(1));
        assertEquals(0, section.materialId(2));
        assertThrows(IllegalArgumentException.class,
                () -> new RasterLightingVolume.Section(new int[4096], new char[1]));
    }

    @Test void immutablePublicationAndUnknownCells() {
        int[] pixels = new int[4096]; pixels[0] = RasterLightingVolume.AIR;
        var section = new RasterLightingVolume.Section(pixels);
        var slots = new RasterLightingVolume.Section[RasterLightingVolume.SLOTS]; slots[0] = section;
        var volume = new RasterLightingVolume(0,0,0,1,slots);
        pixels[0] = -1; slots[0] = null;
        assertEquals(RasterLightingVolume.AIR, volume.section(0).voxel(0));
        assertEquals(0, volume.section(0).voxel(1));
        assertNull(volume.section(1));
        assertThrows(IllegalArgumentException.class, () -> new RasterLightingVolume.Section(new int[1]));
        assertThrows(IllegalArgumentException.class, () -> new RasterLightingVolume(0,0,0,0,new RasterLightingVolume.Section[1]));
    }

    @Test void profileNeverEnablesFullRendererAndKeepsOldNamesStable() throws Exception {
        String settings = source("render/RendererSettings.java");
        assertTrue(settings.contains("MINECRAFT_PURE,\n        MINECRAFT_RGB,\n        TOTEM_LUMEN,\n        RASTER_RAY;"));
        assertTrue(settings.contains("return renderProfile == RenderProfile.TOTEM_LUMEN;"));
        assertTrue(settings.contains("return renderProfile == RenderProfile.RASTER_RAY;"));
        String renderer = source("integration/RasterLightingRenderer.java");
        assertTrue(renderer.contains("if (!RendererSettings.rasterLightingEnabled() || failed || surface == null) return;"));
        assertFalse(renderer.contains("P5StableLookupRenderer"));
        assertFalse(renderer.contains("ClientLevel"));
        assertFalse(renderer.contains("getBlockState"));
        String indirect = source("integration/RasterIndirectGiStage.java");
        assertTrue(indirect.contains("GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING"));
        assertTrue(indirect.contains("| GpuTexture.USAGE_RENDER_ATTACHMENT"));
        assertFalse(renderer.contains("createTexture("));
        assertTrue(source("integration/RgbFrameMetrics.java").contains("if (RendererSettings.rasterLightingEnabled()) return RasterLightingRenderer.ready();"));
    }

    @Test void shadersHaveSecondaryRayBudgetAndKeepFarGeometry() throws Exception {
        String lighting = Files.readString(Path.of("src/client/resources/assets/totem-lumen/shaders/core/raster_indirect_gi.fsh"));
        String composite = Files.readString(Path.of("src/client/resources/assets/totem-lumen/shaders/core/raster_ray_composite.fsh"));
        assertTrue(lighting.contains("i < 64"));
        assertTrue(lighting.contains("i < 3"));
        assertTrue(lighting.contains("vec3 origin = p + ModelOffset + normal * 0.08"));
        assertTrue(composite.contains("distance >= TextureMat[0].x) discard"));
        assertTrue(composite.contains("lighting.a <= 0.0"));
        assertFalse(lighting.contains("SceneSampler"));
        assertTrue(composite.contains("texture(SceneSampler, texCoord).rgb * lighting.rgb"));
        assertTrue(source("integration/RasterLightingScene.java").contains("n < RasterLightingWindow.SECTIONS_PER_TICK"));
        assertTrue(source("integration/RasterLightingScene.java").contains("2_000_000L"));
        assertTrue(source("integration/SceneExtractionBridge.java").contains("return RendererSettings.rendererEnabled() &&"));
    }

    @Test void disconnectDoesNotDestroyRasterResourcesOnNetty() throws Exception {
        String client = source("TotemLumenClient.java");
        int start = client.indexOf("ClientPlayConnectionEvents.DISCONNECT.register");
        int end = client.indexOf("\n        });", start);
        assertTrue(start >= 0 && end > start);
        String callback = client.substring(start, end);
        assertFalse(callback.contains("RasterLightingRenderer.close()"));
        assertFalse(callback.contains("RasterLightingScene.clear()"));
        assertTrue(client.contains("RasterLightingRenderer.tickLifecycle(client)"));
        assertTrue(client.contains("RasterSurfaceCapture.tickLifecycle(client)"));
    }

    private static String source(String path) throws Exception {
        return Files.readString(Path.of("src/client/java/dev/totem/lumen/" + path));
    }
}
