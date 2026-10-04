package dev.totem.lumen.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HybridTerrainPolicyTest {
    @Test void missesAndInvalidValuesPreserveRasterTerrain() {
        for (float distance : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertEquals(0, HybridTerrainPolicy.weight(distance, 32));
        }
        assertEquals(0, HybridTerrainPolicy.weight(10, 0));
        assertEquals(0, HybridTerrainPolicy.weight(10, Float.NaN));
    }

    @Test void lightingFadesBeforeBoundaryWithoutAffectingRasterVisibility() {
        assertEquals(1, HybridTerrainPolicy.weight(20, 32));
        assertEquals(0.5f, HybridTerrainPolicy.weight(28.8f, 32), 0.00001f);
        assertEquals(0, HybridTerrainPolicy.weight(32, 32));
        assertEquals(0, HybridTerrainPolicy.weight(128, 32));
        float last = 1;
        for (int i = 1; i <= 640; i++) {
            float next = HybridTerrainPolicy.weight(i / 10f, 32);
            assertTrue(next >= 0 && next <= last);
            last = next;
        }
    }

    @Test void outputPlanesAreBoundedAndBaselineLayoutUnchanged() {
        assertEquals(288, HybridTerrainPolicy.outputRows(288, false));
        assertEquals(576, HybridTerrainPolicy.outputRows(288, true));
        assertThrows(IllegalArgumentException.class, () -> HybridTerrainPolicy.outputRows(0, true));
        assertThrows(ArithmeticException.class, () -> HybridTerrainPolicy.outputRows(Integer.MAX_VALUE, true));
    }
}
