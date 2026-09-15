package dev.totem.lumen.scene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockGeometryCodeTest {
    @Test
    void stairCodePreservesFamilyDirectionHalfAndShape() {
        int code = BlockGeometryCode.stairs(
                BlockGeometryCode.WEST,
                true,
                BlockGeometryCode.STAIR_INNER_RIGHT
        );

        assertEquals(BlockGeometryCode.STAIRS, BlockGeometryCode.family(code));
        assertEquals(BlockGeometryCode.WEST, code & 0x3);
        assertTrue((code & BlockGeometryCode.STAIR_TOP) != 0);
        assertEquals(
                BlockGeometryCode.STAIR_INNER_RIGHT,
                (code >> BlockGeometryCode.STAIR_SHAPE_SHIFT) & 0x7
        );
        assertTrue(BlockGeometryCode.isKnown(code));
    }

    @Test
    void wallCodeKeepsFourSideStatesAndCenterPost() {
        int code = BlockGeometryCode.wall(1, 2, 0, 1, true);
        int params = code & BlockGeometryCode.PARAM_MASK;

        assertEquals(BlockGeometryCode.WALL, BlockGeometryCode.family(code));
        assertEquals(1, params & 0x3);
        assertEquals(2, (params >> 2) & 0x3);
        assertEquals(0, (params >> 4) & 0x3);
        assertEquals(1, (params >> 6) & 0x3);
        assertTrue((params & BlockGeometryCode.WALL_UP) != 0);
    }

    @Test
    void connectionFamiliesStayWithinLowFourBits() {
        int all = BlockGeometryCode.CONNECT_NORTH
                | BlockGeometryCode.CONNECT_EAST
                | BlockGeometryCode.CONNECT_SOUTH
                | BlockGeometryCode.CONNECT_WEST;

        assertEquals(BlockGeometryCode.FENCE | 0xF, BlockGeometryCode.fence(all));
        assertEquals(BlockGeometryCode.PANE | 0xF, BlockGeometryCode.pane(all));
    }

    @Test
    void doorAndTrapdoorStateBitsDoNotChangeFamily() {
        int door = BlockGeometryCode.door(BlockGeometryCode.SOUTH, true, true);
        int trapdoor = BlockGeometryCode.trapdoor(BlockGeometryCode.EAST, true, true);

        assertEquals(BlockGeometryCode.DOOR, BlockGeometryCode.family(door));
        assertEquals(BlockGeometryCode.TRAPDOOR, BlockGeometryCode.family(trapdoor));
        assertTrue(BlockGeometryCode.isKnown(door));
        assertTrue(BlockGeometryCode.isKnown(trapdoor));
    }

    @Test
    void invalidPackedParametersFailFast() {
        assertThrows(IllegalArgumentException.class, () -> BlockGeometryCode.stairs(4, false, 0));
        assertThrows(IllegalArgumentException.class, () -> BlockGeometryCode.wall(3, 0, 0, 0, false));
    }
}
