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

        scene.apply(new SceneUpdate.ChunkLoaded(5, "minecraft:overworld", 4, 4));
        assertEquals(0, scene.loadedChunkCount());

        scene.apply(new SceneUpdate.ChunkLoaded(6, "minecraft:the_nether", 4, 4));
        assertEquals(1, scene.loadedChunkCount());
    }

    @Test
    void blockChangeMarksOneInteriorSection() {
        RayScene scene = new RayScene();
        scene.apply(new SceneUpdate.LevelChanged(1, "minecraft:overworld"));
        scene.apply(new SceneUpdate.BlockChanged(2, "minecraft:overworld", 8, 40, 8, 2));

        assertEquals(1, scene.dirtySectionCount());
        assertTrue(scene.isSectionDirty(new SectionKey("minecraft:overworld", 0, 2, 0)));
        assertEquals(1, scene.blockChangeCount());
    }

    @Test
    void blockChangeOnSectionCornerMarksEightSections() {
        RayScene scene = new RayScene();
        scene.apply(new SceneUpdate.LevelChanged(1, "minecraft:overworld"));
        scene.apply(new SceneUpdate.BlockChanged(2, "minecraft:overworld", 16, 32, 16, 2));

        assertEquals(8, scene.dirtySectionCount());
        assertTrue(scene.isSectionDirty(new SectionKey("minecraft:overworld", 0, 1, 0)));
        assertTrue(scene.isSectionDirty(new SectionKey("minecraft:overworld", 1, 2, 1)));
    }

    @Test
    void negativeCoordinatesUseFloorDivision() {
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
    void unloadingChunkRemovesItsDirtySections() {
        RayScene scene = new RayScene();
        scene.apply(new SceneUpdate.LevelChanged(1, "minecraft:overworld"));
        scene.apply(new SceneUpdate.ChunkLoaded(2, "minecraft:overworld", 0, 0));
        scene.apply(new SceneUpdate.BlockChanged(3, "minecraft:overworld", 8, 8, 8, 2));
        assertFalse(scene.dirtySectionCount() == 0);

        scene.apply(new SceneUpdate.ChunkUnloaded(4, "minecraft:overworld", 0, 0));
        assertEquals(0, scene.dirtySectionCount());
    }

    @Test
    void levelClearDetachesScene() {
        RayScene scene = new RayScene();
        scene.apply(new SceneUpdate.LevelChanged(1, "minecraft:overworld"));
        scene.apply(new SceneUpdate.ChunkLoaded(2, "minecraft:overworld", 0, 0));
        scene.apply(new SceneUpdate.BlockChanged(3, "minecraft:overworld", 0, 64, 0, 2));
        scene.apply(new SceneUpdate.LevelCleared(4));

        assertNull(scene.activeDimension());
        assertEquals(0, scene.loadedChunkCount());
        assertEquals(0, scene.dirtySectionCount());
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
