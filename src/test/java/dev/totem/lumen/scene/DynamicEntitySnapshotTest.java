package dev.totem.lumen.scene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamicEntitySnapshotTest {
    @Test
    void derivesWorldBoundsFromEntityLocalQuads() {
        DynamicEntitySnapshot snapshot = new DynamicEntitySnapshot(
                7L,
                "minecraft:overworld",
                "minecraft:zombie",
                32.0,
                64.0,
                -16.0,
                new float[] {
                        -0.5f, 0.0f, -0.25f,
                         0.5f, 0.0f, -0.25f,
                         0.5f, 1.8f,  0.25f,
                        -0.5f, 1.8f,  0.25f
                }
        );

        assertEquals(31.5f, snapshot.minX());
        assertEquals(64.0f, snapshot.minY());
        assertEquals(-16.25f, snapshot.minZ());
        assertEquals(32.5f, snapshot.maxX());
        assertEquals(65.8f, snapshot.maxY(), 0.0001f);
        assertEquals(-15.75f, snapshot.maxZ());
        assertEquals(1, snapshot.quadCount());
    }

    @Test
    void snapshotDefensivelyCopiesGeometry() {
        float[] positions = {
                0.0f, 0.0f, 0.0f,
                1.0f, 0.0f, 0.0f,
                1.0f, 1.0f, 0.0f,
                0.0f, 1.0f, 0.0f
        };
        DynamicEntitySnapshot snapshot = new DynamicEntitySnapshot(
                1L,
                "minecraft:overworld",
                "minecraft:player",
                0.0,
                0.0,
                0.0,
                positions
        );

        positions[0] = 99.0f;
        float[] copy = snapshot.copyQuadPositions();
        assertEquals(0.0f, copy[0]);
        copy[0] = 42.0f;
        assertArrayEquals(new float[] {
                0.0f, 0.0f, 0.0f,
                1.0f, 0.0f, 0.0f,
                1.0f, 1.0f, 0.0f,
                0.0f, 1.0f, 0.0f
        }, snapshot.copyQuadPositions());
    }

    @Test
    void retainsOneUvPairPerCapturedVertex() {
        float[] positions = {
                0.0f, 0.0f, 0.0f,
                1.0f, 0.0f, 0.0f,
                1.0f, 1.0f, 0.0f,
                0.0f, 1.0f, 0.0f
        };
        float[] uvs = {
                0.25f, 0.50f,
                0.50f, 0.50f,
                0.50f, 0.75f,
                0.25f, 0.75f
        };
        DynamicEntitySnapshot snapshot = new DynamicEntitySnapshot(
                2L,
                "minecraft:overworld",
                "minecraft:spider",
                0.0,
                0.0,
                0.0,
                positions,
                uvs
        );

        uvs[0] = 9.0f;
        assertArrayEquals(new float[]{
                0.25f, 0.50f,
                0.50f, 0.50f,
                0.50f, 0.75f,
                0.25f, 0.75f
        }, snapshot.copyQuadUvs());
    }

    @Test
    void rejectsNonQuadGeometry() {
        assertThrows(IllegalArgumentException.class, () -> new DynamicEntitySnapshot(
                1L,
                "minecraft:overworld",
                "minecraft:pig",
                0.0,
                0.0,
                0.0,
                new float[] {0.0f, 0.0f, 0.0f}
        ));
    }
}
