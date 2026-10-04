package dev.totem.lumen.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RasterMaterialGpuLayoutTest {
    @Test
    void materialIdsRoundTripAllUnsigned16BitValues() {
        for (int id : new int[]{0, 1, 255, 256, 4095, 32768, 65535}) {
            int texel = RasterMaterialGpuLayout.materialIdTexel(id);
            assertEquals(id, RasterMaterialGpuLayout.materialIdFromTexel(texel));
            assertEquals(255, texel >>> 24);
        }
        assertThrows(IllegalArgumentException.class,
                () -> RasterMaterialGpuLayout.materialIdTexel(-1));
        assertThrows(IllegalArgumentException.class,
                () -> RasterMaterialGpuLayout.materialIdTexel(65536));
    }

    @Test
    void lutMapsEveryMaterialWordWithoutCollision() {
        assertEquals(16, RasterMaterialGpuLayout.WORDS_PER_MATERIAL);
        assertEquals(4096, RasterMaterialGpuLayout.LUT_WIDTH);
        boolean[] firstRow = new boolean[RasterMaterialGpuLayout.LUT_WIDTH];
        for (int id = 0; id < 256; id++) {
            for (int word = 0; word < RasterMaterialGpuLayout.WORDS_PER_MATERIAL; word++) {
                int x = RasterMaterialGpuLayout.lutX(id, word);
                assertFalse(firstRow[x]);
                firstRow[x] = true;
                assertEquals(0, RasterMaterialGpuLayout.lutY(id));
            }
        }
        for (boolean seen : firstRow) assertTrue(seen);
        assertEquals(1, RasterMaterialGpuLayout.lutY(256));
        assertEquals(255, RasterMaterialGpuLayout.lutY(65535));
        assertEquals(256, RasterMaterialGpuLayout.requiredRows(65535));
    }

    @Test
    void lutCoordinatesPreserveStableIdIndexing() {
        assertEquals(0, RasterMaterialGpuLayout.lutX(0, 0));
        assertEquals(15, RasterMaterialGpuLayout.lutX(0, 15));
        assertEquals(16, RasterMaterialGpuLayout.lutX(1, 0));
        assertEquals(4095, RasterMaterialGpuLayout.lutX(255, 15));
        assertEquals(0, RasterMaterialGpuLayout.lutX(256, 0));
        assertEquals(1, RasterMaterialGpuLayout.lutY(256));
    }
}
