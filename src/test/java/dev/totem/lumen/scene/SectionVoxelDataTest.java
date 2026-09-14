package dev.totem.lumen.scene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionVoxelDataTest {
    @Test
    void localCoordinatesPackWithoutCollisionAtCorners() {
        assertEquals(0, SectionVoxelData.index(0, 0, 0));
        assertEquals(15, SectionVoxelData.index(15, 0, 0));
        assertEquals(240, SectionVoxelData.index(0, 0, 15));
        assertEquals(3840, SectionVoxelData.index(0, 15, 0));
        assertEquals(4095, SectionVoxelData.index(15, 15, 15));
    }

    @Test
    void dataIsDefensivelyCopied() {
        int[] ids = new int[SectionVoxelData.VOXEL_COUNT];
        ids[SectionVoxelData.index(1, 2, 3)] = 42;
        SectionVoxelData data = new SectionVoxelData(ids);
        ids[SectionVoxelData.index(1, 2, 3)] = 7;

        assertEquals(42, data.materialId(1, 2, 3));
    }

    @Test
    void emptySectionIsAllAir() {
        assertTrue(SectionVoxelData.empty().isAllAir());
    }

    @Test
    void invalidLocalCoordinateFailsFast() {
        assertThrows(IndexOutOfBoundsException.class, () -> SectionVoxelData.index(-1, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> SectionVoxelData.index(16, 0, 0));
    }
}
