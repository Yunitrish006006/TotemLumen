package dev.totem.lumen.render;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class LumiaVoxelAtlasTest {
    private final LumiaVoxelAtlas atlas = new LumiaVoxelAtlas();
    private final RasterLightingVolume.Section[] gpu = new RasterLightingVolume.Section[RasterLightingVolume.SLOTS];
    private int uploads;

    private void frame(RasterLightingWindow window) {
        uploads = 0;
        atlas.update(window.snapshot(), (tile, section) -> {
            gpu[tile] = section;
            uploads++;
        });
        assertTrue(uploads <= RasterLightingWindow.UPLOADS_PER_FRAME);
        for (int i = 0; i < gpu.length; i++) {
            int entry = atlas.entry(i);
            if (entry != 0) assertSame(window.section(i), gpu[entry - 1]);
        }
    }

    private void populate(RasterLightingWindow window) {
        for (int i = 0; i < gpu.length; i++) window.put(i, new RasterLightingVolume.Section(new int[4096]));
        do { frame(window); } while (uploads > 0);
    }

    @Test void overlappingSectionsRemainVisibleOnFirstFrameInEitherDirection() {
        var start = new RasterLightingWindow(-48, -64, -32, 1, null);
        populate(start);
        for (int[] step : new int[][] {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1},{1,1,-1}}) {
            var moved = new RasterLightingWindow(-48 + step[0]*16, -64 + step[1]*16, -32 + step[2]*16, 2, start);
            frame(moved);
            assertEquals(0, uploads);
            for (int i = 0; i < gpu.length; i++) assertEquals(moved.section(i) != null, atlas.entry(i) != 0);
            frame(start);
            assertEquals(0, uploads);
        }
    }

    @Test void teleportAndDirtySectionsNeverExposeDisplacedTiles() {
        var start = new RasterLightingWindow(0, 0, 0, 1, null);
        populate(start);
        var moved = new RasterLightingWindow(96, 0, 0, 2, start);
        for (int i = 0; i < gpu.length; i++) moved.put(i, new RasterLightingVolume.Section(new int[4096]));
        frame(moved);
        assertEquals(RasterLightingWindow.UPLOADS_PER_FRAME, uploads);
        for (int i = uploads; i < gpu.length; i++) assertEquals(0, atlas.entry(i));
        moved.invalidate(moved.sectionX(0), moved.sectionY(0), moved.sectionZ(0));
        frame(moved);
        assertEquals(0, atlas.entry(0));
        atlas.clear();
        Arrays.fill(gpu, null);
        frame(moved);
        assertEquals(RasterLightingWindow.UPLOADS_PER_FRAME, uploads);
    }
}
