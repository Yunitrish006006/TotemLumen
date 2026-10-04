package dev.totem.lumen.render;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RasterDirectLightsTest {
    private static final int CYAN = RasterLightingVolume.packEmission(false, 0, 1, 1, 1);

    @Test void representativesAreOptInBoundedAndImmutable() {
        int[] pixels = new int[4096];
        java.util.Arrays.fill(pixels, CYAN);
        assertEquals(0, new RasterLightingVolume.Section(pixels).emitterCount());
        var section = new RasterLightingVolume.Section(pixels, true);
        assertEquals(8, section.emitterCount());
        java.util.Arrays.fill(pixels, 0);
        for (int i = 0; i < 8; i++) {
            int index = section.emitter(i);
            assertTrue(index >= 0);
            assertEquals(CYAN, section.voxel(index));
            assertEquals(i, ((index & 15) >>> 3) | ((((index >>> 4) & 15) >>> 3) << 1) | ((index >>> 11) << 2));
        }
    }

    @Test void singleSmallEmitterSurvivesAndPackingUsesVolumeLocalCoordinates() {
        var sections = new RasterLightingVolume.Section[216];
        int[] pixels = new int[4096];
        pixels[RasterLightingVolume.index(3, 2, 1)] = CYAN;
        sections[RasterLightingVolume.slot(2, 3, 4)] = new RasterLightingVolume.Section(pixels, true);
        var volume = new RasterLightingVolume(-48, -64, -48, 1, sections);
        var lights = RasterDirectLights.select(volume, 35, 50, 65);
        assertEquals(1, lights.size());
        assertEquals(new RasterDirectLights.Light(35, 50, 65, CYAN), lights.getFirst());
        assertEquals(0xff413223, lights.getFirst().positionTexel());
        sections[RasterLightingVolume.slot(2, 3, 4)] = null;
        assertEquals(1, RasterDirectLights.select(volume, 35, 50, 65).size());
        assertTrue(RasterDirectLights.select(new RasterLightingVolume(-48,-64,-48,2,sections),35,50,65).isEmpty());
    }

    @Test void denseWindowIsDeterministicCappedAndHandlesEmptyOrInvalidInput() {
        var sections = new RasterLightingVolume.Section[216];
        int[] pixels = new int[4096]; java.util.Arrays.fill(pixels, CYAN);
        java.util.Arrays.fill(sections, new RasterLightingVolume.Section(pixels, true));
        var volume = new RasterLightingVolume(0, 0, 0, 1, sections);
        var selected = RasterDirectLights.select(volume, 48.5, 48.5, 48.5);
        assertEquals(32, selected.size());
        assertEquals(selected, RasterDirectLights.select(volume, 48.5, 48.5, 48.5));
        assertEquals(32, selected.stream().distinct().count());
        assertTrue(RasterDirectLights.select(volume, Double.NaN, 0, 0).isEmpty());
        assertTrue(RasterDirectLights.select(volume, 10000, 0, 0).isEmpty());
        assertTrue(RasterDirectLights.select(new RasterLightingVolume(0,0,0,1,new RasterLightingVolume.Section[216]),0,0,0).isEmpty());
    }

    @Test void strongerSourceWinsOctantAndUnknownCannotEmit() {
        int[] pixels = new int[4096];
        pixels[0] = RasterLightingVolume.packEmission(false, 1, 0, 0, .2f);
        pixels[1] = CYAN;
        pixels[2] = 0x00ffffff;
        assertEquals(1, new RasterLightingVolume.Section(pixels, true).emitter(0));
    }

    @Test void cameraDistanceReorderingDoesNotChangeTheSameLightPayload() {
        var sections = new RasterLightingVolume.Section[RasterLightingVolume.SLOTS];
        int[] pixels = new int[4096];
        pixels[RasterLightingVolume.index(2, 2, 2)] = CYAN;
        pixels[RasterLightingVolume.index(10, 2, 2)] = RasterLightingVolume.packEmission(false, 1, .2f, 0, 1);
        sections[0] = new RasterLightingVolume.Section(pixels, true);
        var volume = new RasterLightingVolume(-48, -64, -48, 1, sections);
        var fromLeft = RasterDirectLights.select(volume, 2.5, 2.5, 2.5);
        var fromRight = RasterDirectLights.select(volume, 10.5, 2.5, 2.5);
        assertEquals(2, fromLeft.size());
        assertEquals(fromLeft, fromRight, "Distance ranks select membership, not GPU record order");
        assertThrows(UnsupportedOperationException.class, () -> fromLeft.clear());

        // Stable ordering must not conceal a source colour/strength edit from the upload cache.
        pixels[RasterLightingVolume.index(2, 2, 2)] = RasterLightingVolume.packEmission(false, 0, 0, 1, .5f);
        sections[0] = new RasterLightingVolume.Section(pixels, true);
        var changed = RasterDirectLights.select(new RasterLightingVolume(-48,-64,-48,1,sections),2.5,2.5,2.5);
        assertNotEquals(fromLeft, changed);
        assertEquals(CYAN, fromLeft.getFirst().abgr());
    }

    @Test void canonicalPayloadStillSelectsTheNearest32IncludingDeterministicDistanceTies() {
        var sections = new RasterLightingVolume.Section[RasterLightingVolume.SLOTS];
        int[] pixels = new int[4096]; java.util.Arrays.fill(pixels, CYAN);
        java.util.Arrays.fill(sections, new RasterLightingVolume.Section(pixels, true));
        var volume = new RasterLightingVolume(0,0,0,1,sections);
        var coordinates = java.util.Comparator.comparingInt(RasterDirectLights.Light::y)
                .thenComparingInt(RasterDirectLights.Light::z).thenComparingInt(RasterDirectLights.Light::x);
        // Independent exhaustive oracle, not the production heap selection.
        var all = new java.util.ArrayList<RasterDirectLights.Light>();
        for (int y = 0; y < 96; y++) for (int z = 0; z < 96; z++) for (int x = 0; x < 96; x++) {
            if ((x % 8 == 3) && (y % 8 == 3) && (z % 8 == 3))
                all.add(new RasterDirectLights.Light(x,y,z,CYAN));
        }
        for (double camera : new double[]{40.5, 48.5, 56.5}) {
            var nearest = java.util.Comparator.comparingDouble((RasterDirectLights.Light l) ->
                    Math.pow(l.x()+.5-camera,2) + Math.pow(l.y()+.5-camera,2) + Math.pow(l.z()+.5-camera,2))
                    .thenComparing(coordinates);
            var expected = all.stream().sorted(nearest).limit(32).sorted(coordinates).toList();
            assertEquals(expected, RasterDirectLights.select(volume,camera,camera,camera));
        }
    }

    @Test void directLightDataStaysDormantUntilMaterialStageExists() throws Exception {
        String shader = Files.readString(Path.of("src/client/resources/assets/totem-lumen/shaders/core/raster_indirect_gi.fsh"));
        assertFalse(shader.contains("LightSampler"));
        assertFalse(shader.contains("directRgb("));
        assertFalse(shader.contains("visibility("));
        String scene = Files.readString(Path.of("src/client/java/dev/totem/lumen/integration/RasterLightingScene.java"));
        assertTrue(scene.contains("new RasterLightingVolume.Section(pixels, materialIds)"));
        assertFalse(scene.contains("RasterMaterialCapture"));
        String renderer = Files.readString(Path.of("src/client/java/dev/totem/lumen/integration/RasterLightingRenderer.java"));
        assertTrue(renderer.contains("surface.supportsIndependentLighting()"));
        assertTrue(renderer.contains("return false;"));
        String metrics = Files.readString(Path.of("src/client/java/dev/totem/lumen/integration/RgbFrameMetrics.java"));
        assertTrue(metrics.contains("rasterMaterialLighting={}"));
    }
}
