package dev.totem.lumen.scene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                true,
                0xFF3F76E4,
                positions,
                new float[]{0, 0, 1, 0, 1, 1, 0, 1},
                new int[]{0xCC3366FF},
                new boolean[]{false}
        );

        assertEquals(-1, snapshot.sectionX());
        assertEquals(4, snapshot.sectionY());
        assertEquals(2, snapshot.sectionZ());
        assertTrue(snapshot.fluidOnlyCell());
        assertEquals(0xFF3F76E4, snapshot.tintArgb());
        assertEquals(-1.0, snapshot.worldVertexX(0, 0));
        assertEquals(66.75, snapshot.worldVertexY(0, 0));
        assertEquals(33.0, snapshot.worldVertexZ(0, 0));
        assertEquals(0.0, snapshot.worldVertexX(0, 1));
    }

    @Test
    void defensivelyCopiesAllGeometryArrays() {
        float[] positions = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};
        float[] uvs = {0, 0, 1, 0, 1, 1, 0, 1};
        int[] colors = {0xFF2A5EB8};
        boolean[] doubleSided = {true};
        FluidGeometrySnapshot snapshot = new FluidGeometrySnapshot(
                "minecraft:overworld", "minecraft:water", 0, 0, 0, false,
                0xFF3F76E4,
                positions, uvs, colors, doubleSided
        );

        positions[0] = 99;
        uvs[0] = 99;
        colors[0] = 0;
        doubleSided[0] = false;

        assertEquals(0.0F, snapshot.copyQuadPositions()[0]);
        assertEquals(0.0F, snapshot.copyQuadUvs()[0]);
        assertEquals(0xFF2A5EB8, snapshot.copyQuadColors()[0]);
        assertEquals(0xFF3F76E4, snapshot.tintArgb());
        assertTrue(snapshot.copyDoubleSided()[0]);
        assertNotSame(snapshot.copyQuadPositions(), snapshot.copyQuadPositions());
    }

    @Test
    void rejectsMalformedOrNonFiniteGeometry() {
        assertThrows(IllegalArgumentException.class, () -> new FluidGeometrySnapshot(
                "minecraft:overworld", "minecraft:water", 0, 0, 0, true, 0xFFFFFFFF,
                new float[11], new float[8], new int[]{1}, new boolean[]{false}
        ));
        float[] positions = {0, 0, 0, 1, 0, 0, 1, Float.NaN, 0, 0, 1, 0};
        assertThrows(IllegalArgumentException.class, () -> new FluidGeometrySnapshot(
                "minecraft:overworld", "minecraft:water", 0, 0, 0, true, 0xFFFFFFFF,
                positions, new float[8], new int[]{1}, new boolean[]{false}
        ));
    }
}
