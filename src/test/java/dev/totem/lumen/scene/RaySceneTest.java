package dev.totem.lumen.scene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    void levelClearDetachesScene() {
        RayScene scene = new RayScene();
        scene.apply(new SceneUpdate.LevelChanged(1, "minecraft:overworld"));
        scene.apply(new SceneUpdate.ChunkLoaded(2, "minecraft:overworld", 0, 0));
        scene.apply(new SceneUpdate.LevelCleared(3));

        assertNull(scene.activeDimension());
        assertEquals(0, scene.loadedChunkCount());
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
