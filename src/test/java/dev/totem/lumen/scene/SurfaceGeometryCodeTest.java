package dev.totem.lumen.scene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SurfaceGeometryCodeTest {
    @Test
    void packsQuantizedSurfaceResponseWithoutGrowingVoxelWord() {
        int code = BlockGeometryCode.surfaceCube(0.22f, 0.95f);

        assertEquals(BlockGeometryCode.SURFACE_CUBE, BlockGeometryCode.family(code));
        assertTrue(BlockGeometryCode.isKnown(code));
        assertEquals(0.20f, BlockGeometryCode.surfaceRoughness(code), 0.0001f);
        assertEquals(0.93333334f, BlockGeometryCode.surfaceMetallic(code), 0.0001f);
        assertTrue((code & ~0xFFFF) == 0);
    }

    @Test
    void rejectsInvalidSurfaceResponse() {
        assertThrows(IllegalArgumentException.class, () -> BlockGeometryCode.surfaceCube(-0.1f, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> BlockGeometryCode.surfaceCube(0.5f, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> BlockGeometryCode.surfaceRoughness(BlockGeometryCode.FULL_CUBE));
    }
}
