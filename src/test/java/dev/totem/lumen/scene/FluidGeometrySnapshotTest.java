package dev.totem.lumen.scene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class FluidGeometrySnapshotTest {
    @Test
    void preservesSectionRelativePrecisionAcrossNegativeWorldCoordinates() {
        float[] positions = {
                15.0F, 2.75F, 1.0F,
                16.0F, 2.50F, 1.0F,
                16.0F, 2.50F, 2.0F,
                15.0F, 2.75F, 2.0F
        };
        FluidGeometrySnapshot snapshot = new FluidGeometrySnapshot(
                "minecraft:overworld",
                "minecraft:water",
                -1,
                66,
                33,
                positions,
                new float[]{0, 0, 1, 0, 1, 1, 0, 1},
                new int[]{0xCC3366FF},
                new boolean[]{false}
        );

        assertEquals(-1, snapshot.sectionX());
        assertEquals(4, snapshot.sectionY());
        assertEquals(2, snapshot.sectionZ());
        assertEquals(-1.0, snapshot.worldVertexX(0, 0));
        assertEquals(66.75, snapshot.worldVertexY(0, 0));
        assertEquals(33.0, snapshot.worldVertexZ(0, 0));
        assertEquals(0.0, snapshot.worldVertexX(0, 1));
    }

    @Test
    void defensivelyCopiesAllGeometryArrays() {
        float[] positions = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};
        float[] uvs = {0, 0, 1, 0, 1, 1, 0, 1};
        int[] colors = {0xFFFFFFFF};
        boolean[] doubleSided = {true};
        FluidGeometrySnapshot snapshot = new FluidGeometrySnapshot(
                "minecraft:overworld", "minecraft:water", 0, 0, 0,
                positions, uvs, colors, doubleSided
        );

        positions[0] = 99;
        uvs[0] = 99;
        colors[0] = 0;
        doubleSided[0] = false;

        assertEquals(0.0F, snapshot.copyQuadPositions()[0]);
        assertEquals(0.0F, snapshot.copyQuadUvs()[0]);
        assertEquals(0xFFFFFFFF, snapshot.copyQuadColors()[0]);
        assertFalse(snapshot.copyDoubleSided()[0] == false);
        assertNotSame(snapshot.copyQuadPositions(), snapshot.copyQuadPositions());
    }

    @Test
    void rejectsMalformedOrNonFiniteGeometry() {
        assertThrows(IllegalArgumentException.class, () -> new FluidGeometrySnapshot(
                "minecraft:overworld", "minecraft:water", 0, 0, 0,
                new float[11], new float[8], new int[]{1}, new boolean[]{false}
        ));
        float[] positions = {0, 0, 0, 1, 0, 0, 1, Float.NaN, 0, 0, 1, 0};
        assertThrows(IllegalArgumentException.class, () -> new FluidGeometrySnapshot(
                "minecraft:overworld", "minecraft:water", 0, 0, 0,
                positions, new float[8], new int[]{1}, new boolean[]{false}
        ));
    }
}
