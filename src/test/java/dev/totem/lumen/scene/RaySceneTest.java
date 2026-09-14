package dev.totem.lumen.scene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaySceneTest {
    @Test
    void levelChangeResetsChunksAndStaleDimensionLoadsAreIgnored() {
        RayScene scene = new RayScene();
        scene.apply(new SceneUpdate.LevelChanged(1, "minecraft:overworld"));
        scene.apply(new SceneUpdate.ChunkLoaded(2, "minecraft:overworld", 0, 0));
        scene.apply(new SceneUpdate.ChunkLoaded(3, "minecraft:overworld", -1, 2));
        assertEquals(2, scene.loadedChunkCount());

        scene.apply(new SceneUpdate.LevelChanged(4, "minecraft:the_nether"));
        assertEquals("minecraft:the_nether", scene.activeDimension());
        assertEquals(0, scene.loadedChunkCount());
    }

    @Test
    void sectionRebuildInstallsNonAirDataAndClearsDirtyMarker() {
        RayScene scene = new RayScene();
        SectionKey key = new SectionKey("minecraft:overworld", 0, 4, 0);
        scene.apply(new SceneUpdate.LevelChanged(1, "minecraft:overworld"));
        scene.apply(new SceneUpdate.BlockChanged(2, "minecraft:overworld", 8, 72, 8, 2));
        assertTrue(scene.isSectionDirty(key));

        int[] ids = new int[SectionVoxelData.VOXEL_COUNT];
        ids[SectionVoxelData.index(1, 2, 3)] = 7;
        SectionSnapshot snapshot = new SectionSnapshot(key, 1, new SectionVoxelData(ids));
        scene.apply(new SceneUpdate.SectionRebuilt(3, snapshot));

        assertEquals(1, scene.populatedSectionCount());
        assertEquals(snapshot, scene.section(key));
        assertFalse(scene.isSectionDirty(key));
    }

    @Test
    void allAirRebuildRemovesExistingSection() {
        RayScene scene = new RayScene();
        SectionKey key = new SectionKey("minecraft:overworld", 0, 4, 0);
        scene.apply(new SceneUpdate.LevelChanged(1, "minecraft:overworld"));

        int[] ids = new int[SectionVoxelData.VOXEL_COUNT];
        ids[0] = 3;
        scene.apply(new SceneUpdate.SectionRebuilt(
                2, new SectionSnapshot(key, 1, new SectionVoxelData(ids))
        ));
        assertEquals(1, scene.populatedSectionCount());

        scene.apply(new SceneUpdate.SectionRebuilt(
                3, new SectionSnapshot(key, 2, SectionVoxelData.empty())
        ));
        assertEquals(0, scene.populatedSectionCount());
        assertNull(scene.section(key));
    }

    @Test
    void chunkUnloadRemovesSnapshotsAndDirtyMarkers() {
        RayScene scene = new RayScene();
        SectionKey key = new SectionKey("minecraft:overworld", 0, 0, 0);
        scene.apply(new SceneUpdate.LevelChanged(1, "minecraft:overworld"));
        scene.apply(new SceneUpdate.ChunkLoaded(2, "minecraft:overworld", 0, 0));
        scene.apply(new SceneUpdate.BlockChanged(3, "minecraft:overworld", 8, 8, 8, 2));

        int[] ids = new int[SectionVoxelData.VOXEL_COUNT];
        ids[0] = 1;
        scene.apply(new SceneUpdate.SectionRebuilt(
                4, new SectionSnapshot(key, 1, new SectionVoxelData(ids))
        ));
        scene.apply(new SceneUpdate.BlockChanged(5, "minecraft:overworld", 8, 8, 8, 2));
        assertTrue(scene.isSectionDirty(key));

        scene.apply(new SceneUpdate.ChunkUnloaded(6, "minecraft:overworld", 0, 0));
        assertEquals(0, scene.loadedChunkCount());
        assertEquals(0, scene.populatedSectionCount());
        assertEquals(0, scene.dirtySectionCount());
    }

    @Test
    void negativeBlockCoordinatesMarkExpectedSection() {
        RayScene scene = new RayScene();
        scene.apply(new SceneUpdate.LevelChanged(1, "minecraft:overworld"));
        scene.apply(new SceneUpdate.BlockChanged(2, "minecraft:overworld", -8, 8, -8, 2));

        assertEquals(1, scene.dirtySectionCount());
        assertTrue(scene.isSectionDirty(new SectionKey("minecraft:overworld", -1, 0, -1)));
    }

    @Test
    void staleDimensionBlockChangesAreIgnored() {
        RayScene scene = new RayScene();
        scene.apply(new SceneUpdate.LevelChanged(1, "minecraft:the_nether"));
        scene.apply(new SceneUpdate.BlockChanged(2, "minecraft:overworld", 0, 64, 0, 2));

        assertEquals(0, scene.dirtySectionCount());
        assertEquals(0, scene.blockChangeCount());
    }

    @Test
    void levelClearDetachesScene() {
        RayScene scene = new RayScene();
        scene.apply(new SceneUpdate.LevelChanged(1, "minecraft:overworld"));
        scene.apply(new SceneUpdate.ChunkLoaded(2, "minecraft:overworld", 0, 0));
        scene.apply(new SceneUpdate.LevelCleared(3));

        assertNull(scene.activeDimension());
        assertEquals(0, scene.loadedChunkCount());
        assertEquals(0, scene.populatedSectionCount());
    }

    @Test
    void outOfOrderUpdatesAreIgnored() {
        RayScene scene = new RayScene();
        scene.apply(new SceneUpdate.LevelChanged(10, "minecraft:overworld"));
        scene.apply(new SceneUpdate.ChunkLoaded(9, "minecraft:overworld", 1, 1));

        assertEquals(0, scene.loadedChunkCount());
        assertEquals(10, scene.lastAppliedSequence());
    }
}
