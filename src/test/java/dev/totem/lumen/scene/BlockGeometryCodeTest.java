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
    void transmissivePaneKeepsConnectionsAndTint() {
        int code = BlockGeometryCode.transmissivePane(
                BlockGeometryCode.CONNECT_NORTH | BlockGeometryCode.CONNECT_WEST,
                BlockGeometryCode.TINT_RED
        );

        assertEquals(BlockGeometryCode.PANE, BlockGeometryCode.family(code));
        assertEquals(
                BlockGeometryCode.CONNECT_NORTH | BlockGeometryCode.CONNECT_WEST,
                code & 0xF
        );
        assertTrue((code & BlockGeometryCode.PANE_TRANSMISSIVE) != 0);
        assertEquals(BlockGeometryCode.TINT_RED, BlockGeometryCode.transmissionTint(code));
        assertTrue(BlockGeometryCode.isKnown(code));
    }

    @Test
    void glassCubePreservesFullCubeTransmissionTint() {
        int clear = BlockGeometryCode.glassCube(BlockGeometryCode.TINT_CLEAR);
        int cyan = BlockGeometryCode.glassCube(BlockGeometryCode.TINT_CYAN);

        assertEquals(BlockGeometryCode.GLASS_CUBE, BlockGeometryCode.family(clear));
        assertEquals(BlockGeometryCode.GLASS_CUBE, BlockGeometryCode.family(cyan));
        assertEquals(BlockGeometryCode.TINT_CLEAR, BlockGeometryCode.transmissionTint(clear));
        assertEquals(BlockGeometryCode.TINT_CYAN, BlockGeometryCode.transmissionTint(cyan));
        assertTrue(BlockGeometryCode.isKnown(clear));
        assertTrue(BlockGeometryCode.isKnown(cyan));
    }

    @Test
    void doorTrapdoorAndFenceGateStateBitsDoNotChangeFamily() {
        int door = BlockGeometryCode.door(BlockGeometryCode.SOUTH, true, true);
        int trapdoor = BlockGeometryCode.trapdoor(BlockGeometryCode.EAST, true, true);
        int fenceGate = BlockGeometryCode.fenceGate(BlockGeometryCode.WEST, true, true);

        assertEquals(BlockGeometryCode.DOOR, BlockGeometryCode.family(door));
        assertEquals(BlockGeometryCode.TRAPDOOR, BlockGeometryCode.family(trapdoor));
        assertEquals(BlockGeometryCode.FENCE_GATE, BlockGeometryCode.family(fenceGate));
        assertEquals(BlockGeometryCode.WEST, fenceGate & 0x3);
        assertTrue((fenceGate & BlockGeometryCode.OPEN) != 0);
        assertTrue((fenceGate & BlockGeometryCode.IN_WALL) != 0);
        assertTrue(BlockGeometryCode.isKnown(door));
        assertTrue(BlockGeometryCode.isKnown(trapdoor));
        assertTrue(BlockGeometryCode.isKnown(fenceGate));
    }

    @Test
    void invalidPackedParametersFailFast() {
        assertThrows(IllegalArgumentException.class, () -> BlockGeometryCode.stairs(4, false, 0));
        assertThrows(IllegalArgumentException.class, () -> BlockGeometryCode.wall(3, 0, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> BlockGeometryCode.fenceGate(-1, false, false));
        assertThrows(IllegalArgumentException.class, () -> BlockGeometryCode.transmissivePane(0, 32));
    }
}
