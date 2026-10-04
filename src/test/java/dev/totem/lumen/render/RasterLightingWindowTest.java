package dev.totem.lumen.render;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class RasterLightingWindowTest {
    private static RasterLightingWindow populated(int x, int y, int z) {
        var window = new RasterLightingWindow(x, y, z, 1, null);
        for (int slot = 0; slot < RasterLightingVolume.SLOTS; slot++) {
            int[] pixels = new int[4096]; pixels[0] = slot + 1;
            window.put(slot, new RasterLightingVolume.Section(pixels));
        }
        return window;
    }

    @Test void oneSectionMoveReuses180SectionsAtExactWorldCoordinates() {
        var original = populated(-48, -80, -48);
        for (int[] shift : new int[][]{{16,0,0},{-16,0,0},{0,16,0},{0,-16,0},{0,0,16},{0,0,-16}}) {
            var moved = new RasterLightingWindow(-48+shift[0], -80+shift[1], -48+shift[2], 2, original);
            assertEquals(180, moved.retainedSections());
            for (int slot = 0; slot < RasterLightingVolume.SLOTS; slot++) {
                var section = moved.section(slot);
                if (section == null) continue;
                int oldSlot = section.voxel(0) - 1;
                assertSame(original.section(oldSlot), section);
                assertEquals(original.sectionX(oldSlot), moved.sectionX(slot));
                assertEquals(original.sectionY(oldSlot), moved.sectionY(slot));
                assertEquals(original.sectionZ(oldSlot), moved.sectionZ(slot));
            }
        }
    }

    @Test void teleportsAndWorldResetDoNotReuseData() {
        var original = populated(-48,-48,-48);
        assertEquals(0, new RasterLightingWindow(48,-48,-48,2,original).retainedSections());
        assertEquals(0, new RasterLightingWindow(-48,-48,-48,2,null).retainedSections());
        assertThrows(IllegalArgumentException.class, () -> new RasterLightingWindow(1,0,0,1,null));
    }

    @Test void invalidationIsCoalescedAndPublishedSnapshotsRemainImmutable() {
        var window = populated(-48,-48,-48);
        var before = window.snapshot();
        for (int i = 0; i < 10000; i++) assertTrue(window.invalidate(0,0,0));
        int center = RasterLightingVolume.slot(3,3,3);
        assertNotNull(before.section(center));
        assertNull(window.snapshot().section(center));
        assertEquals(center, window.next());
        assertFalse(window.invalidate(1000,0,0));
        var moved = new RasterLightingWindow(-32,-48,-48,2,window);
        assertNull(moved.section(RasterLightingVolume.slot(2,3,3)));
    }

    @Test void initialCapturePrioritizesCameraAndResidentRefreshCannotStarve() {
        var empty = new RasterLightingWindow(-48,-48,-48,1,null);
        assertEquals(RasterLightingVolume.slot(3,3,3), empty.next());
        var window = populated(-48,-48,-48);
        var refreshed = new HashSet<Integer>();
        for (int i = 0; i < RasterLightingVolume.SLOTS * 4; i++) {
            window.invalidate(0,0,0);
            int slot = window.next();
            refreshed.add(slot);
            window.put(slot, new RasterLightingVolume.Section(new int[4096]));
        }
        assertEquals(RasterLightingVolume.SLOTS, refreshed.size());
        assertEquals(2, RasterLightingWindow.SECTIONS_PER_TICK);
        assertEquals(8 * 16 * 1024, RasterLightingWindow.UPLOADS_PER_FRAME * 4096 * 4);
    }

    @Test void unchangedRefreshReusesPublishedIdentityWithoutSkippingRefreshWork() {
        var window = populated(-48,-48,-48);
        var published = window.snapshot();
        var refreshed = new HashSet<Integer>();
        for (int n = 0; n < RasterLightingVolume.SLOTS * 2; n++) {
            int slot = window.next();
            refreshed.add(slot);
            window.put(slot, window.section(slot));
            assertSame(published, window.snapshot());
        }
        assertEquals(RasterLightingVolume.SLOTS, refreshed.size());
        assertFalse(window.invalidate(1000,0,0));
        assertSame(published, window.snapshot());
    }

    @Test void changedRemovedAndRestoredSectionsPublishNewImmutableSnapshots() {
        var window = populated(-48,-48,-48);
        int slot = RasterLightingVolume.slot(3,3,3);
        var original = window.snapshot();
        var replacement = new RasterLightingVolume.Section(new int[4096]);
        window.put(slot, replacement);
        var edited = window.snapshot();
        assertNotSame(original, edited);
        assertSame(replacement, edited.section(slot));
        assertNotSame(replacement, original.section(slot));
        window.invalidate(0,0,0);
        var invalid = window.snapshot();
        assertNotSame(edited, invalid);
        assertNull(invalid.section(slot));
        window.invalidate(0,0,0);
        assertSame(invalid, window.snapshot(), "Repeated invalidation of unknown data is not a content change");
        assertEquals(slot, window.next());
        window.put(slot, replacement);
        var restored = window.snapshot();
        assertNotSame(invalid, restored);
        assertSame(replacement, restored.section(slot));
        assertNull(invalid.section(slot));
    }

    @Test void rebaseAndWorldResetNeverReuseTheOldSnapshotIdentity() {
        var window = populated(-48,-48,-48);
        var original = window.snapshot();
        var moved = new RasterLightingWindow(-32,-48,-48,2,window).snapshot();
        assertNotSame(original, moved);
        assertEquals(-32, moved.x);
        assertEquals(2, moved.epoch);
        var reset = new RasterLightingWindow(-48,-48,-48,3,null);
        assertNotSame(original, reset.snapshot());
        assertNull(reset.snapshot().section(0));
        assertSame(reset.snapshot(), reset.snapshot());
    }

    @Test void rgbChannelsSurviveCaptureRebaseAndReplacementIndependently() {
        assertEquals(0xff0000ff, RasterLightingVolume.packRgbSource(true, 0xf00f));
        assertEquals(0x8000ff00, RasterLightingVolume.packRgbSource(false, 0xf0f0));
        assertEquals(0x80ff0000, RasterLightingVolume.packRgbSource(false, 0xff00));
        assertEquals(0xff000000, RasterLightingVolume.packRgbSource(true, 0));
        assertEquals(0xff0000ff, RasterLightingVolume.packEmission(true,1,0,0,1));
        assertEquals(0x8000ff00, RasterLightingVolume.packEmission(false,0,1,0,1));
        assertEquals(0x80800000, RasterLightingVolume.packEmission(false,0,0,1,0.5f));
        assertEquals(RasterLightingVolume.AIR, RasterLightingVolume.packEmission(false,1,1,1,0));
        assertEquals(RasterLightingVolume.AIR, RasterLightingVolume.packEmission(false,Float.NaN,0,0,1));
        var window = populated(-48,-48,-48);
        int center = RasterLightingVolume.slot(3,3,3);
        int[] pixels = new int[4096]; pixels[0] = RasterLightingVolume.packEmission(true,.2f,.6f,1,1);
        window.put(center, new RasterLightingVolume.Section(pixels));
        var moved = new RasterLightingWindow(-32,-48,-48,2,window);
        assertEquals(pixels[0], moved.section(RasterLightingVolume.slot(2,3,3)).voxel(0));
    }

    @Test void rgbUsesStatelessProfileSourceWithoutStartingOtherProfileWork() throws Exception {
        String scene = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/client/java/dev/totem/lumen/integration/RasterLightingScene.java"));
        assertTrue(scene.contains("ClientRgbVisualLightSource.packedFor(state)"));
        assertFalse(scene.contains("ClientGameplayLightPredictor"));
        assertTrue(scene.contains("!RendererSettings.rasterLightingEnabled()"));
        String client = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/client/java/dev/totem/lumen/TotemLumenClient.java"));
        assertTrue(client.contains("if (RendererSettings.rasterLightingEnabled()) RasterLightingScene.requestRefresh()"));
    }

    @Test void plannedCoverageCoversViewCornersWithinFixedLevelBudget() {
        for (int chunks = 1; chunks <= 32; chunks++) {
            var levels = RasterLightingCoverage.forViewDistance(chunks);
            assertEquals(1, levels.getFirst().cellSize());
            assertTrue(levels.size() <= 6);
            assertTrue(levels.getLast().guaranteedRadius() >= Math.sqrt(2) * (chunks+1) * 16);
            assertTrue(RasterLightingCoverage.atlasBytes(chunks) <= 6L * 256 * 3584 * 4);
            assertThrows(UnsupportedOperationException.class, () -> levels.clear());
        }
        assertThrows(IllegalArgumentException.class, () -> RasterLightingCoverage.forViewDistance(0));
        assertThrows(IllegalArgumentException.class, () -> RasterLightingCoverage.forViewDistance(33));
    }
}
