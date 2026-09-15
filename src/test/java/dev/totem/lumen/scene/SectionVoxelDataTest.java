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
        assertEquals(BlockGeometryCode.FULL_CUBE, data.geometryCode(1, 2, 3));
    }

    @Test
    void packedVoxelWordPreservesMaterialAndGeometryIndependently() {
        int word = SectionVoxelData.packVoxelWord(4095, BlockGeometryCode.SLAB_TOP);
        int[] words = new int[SectionVoxelData.VOXEL_COUNT];
        words[SectionVoxelData.index(4, 5, 6)] = word;
        SectionVoxelData data = new SectionVoxelData(words);

        assertEquals(4095, data.materialId(4, 5, 6));
        assertEquals(BlockGeometryCode.SLAB_TOP, data.geometryCode(4, 5, 6));
        assertEquals(4095, SectionVoxelData.unpackMaterialId(word));
        assertEquals(BlockGeometryCode.SLAB_TOP, SectionVoxelData.unpackGeometryCode(word));
        assertEquals(word, data.copyMaterialIds()[SectionVoxelData.index(4, 5, 6)]);
        assertEquals(4095, data.copyRawMaterialIds()[SectionVoxelData.index(4, 5, 6)]);
    }

    @Test
    void packedVoxelWordRejectsValuesOutsideSixteenBits() {
        assertThrows(IllegalArgumentException.class, () -> SectionVoxelData.packVoxelWord(0x1_0000, 0));
        assertThrows(IllegalArgumentException.class, () -> SectionVoxelData.packVoxelWord(1, 0x1_0000));
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
