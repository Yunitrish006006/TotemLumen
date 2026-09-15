package dev.totem.lumen.scene;

/**
 * Compact block-local geometry identifiers packed into the high 16 bits of each GPU voxel word.
 *
 * <p>Code zero intentionally means the legacy/full-cube fast path so existing snapshots remain
 * valid. P14B reserves the upper nibble as a geometry family and the low 12 bits as family-specific
 * parameters. The two P14A slab codes remain stable for compatibility with the first runtime gate.</p>
 */
public final class BlockGeometryCode {
    public static final int FULL_CUBE = 0;
    public static final int SLAB_BOTTOM = 1;
    public static final int SLAB_TOP = 2;

    public static final int FAMILY_MASK = 0xF000;
    public static final int PARAM_MASK = 0x0FFF;

    public static final int STAIRS = 0x1000;
    public static final int FENCE = 0x2000;
    public static final int WALL = 0x3000;
    public static final int PANE = 0x4000;
    public static final int DOOR = 0x5000;
    public static final int TRAPDOOR = 0x6000;

    public static final int NORTH = 0;
    public static final int EAST = 1;
    public static final int SOUTH = 2;
    public static final int WEST = 3;

    public static final int CONNECT_NORTH = 1;
    public static final int CONNECT_EAST = 1 << 1;
    public static final int CONNECT_SOUTH = 1 << 2;
    public static final int CONNECT_WEST = 1 << 3;

    public static final int STAIR_TOP = 1 << 2;
    public static final int STAIR_SHAPE_SHIFT = 3;

    public static final int STAIR_STRAIGHT = 0;
    public static final int STAIR_INNER_LEFT = 1;
    public static final int STAIR_INNER_RIGHT = 2;
    public static final int STAIR_OUTER_LEFT = 3;
    public static final int STAIR_OUTER_RIGHT = 4;

    public static final int WALL_UP = 1 << 8;

    public static final int OPEN = 1 << 2;
    public static final int HINGE_RIGHT = 1 << 3;
    public static final int TOP_HALF = 1 << 3;

    private BlockGeometryCode() {
    }

    public static int stairs(int facing, boolean top, int shape) {
        return STAIRS
                | directionBits(facing)
                | (top ? STAIR_TOP : 0)
                | ((shape & 0x7) << STAIR_SHAPE_SHIFT);
    }

    public static int fence(int connections) {
        return FENCE | (connections & 0xF);
    }

    /**
     * Packs four wall-side states (0 none, 1 low, 2 tall) in N/E/S/W order plus the center-post bit.
     */
    public static int wall(int north, int east, int south, int west, boolean up) {
        return WALL
                | (wallSide(north))
                | (wallSide(east) << 2)
                | (wallSide(south) << 4)
                | (wallSide(west) << 6)
                | (up ? WALL_UP : 0);
    }

    public static int pane(int connections) {
        return PANE | (connections & 0xF);
    }

    public static int door(int facing, boolean open, boolean hingeRight) {
        return DOOR
                | directionBits(facing)
                | (open ? OPEN : 0)
                | (hingeRight ? HINGE_RIGHT : 0);
    }

    public static int trapdoor(int facing, boolean open, boolean top) {
        return TRAPDOOR
                | directionBits(facing)
                | (open ? OPEN : 0)
                | (top ? TOP_HALF : 0);
    }

    public static int family(int geometryCode) {
        return geometryCode & FAMILY_MASK;
    }

    public static boolean isKnown(int geometryCode) {
        if (geometryCode == FULL_CUBE || geometryCode == SLAB_BOTTOM || geometryCode == SLAB_TOP) {
            return true;
        }
        return switch (family(geometryCode)) {
            case STAIRS, FENCE, WALL, PANE, DOOR, TRAPDOOR -> true;
            default -> false;
        };
    }

    private static int directionBits(int facing) {
        if (facing < NORTH || facing > WEST) {
            throw new IllegalArgumentException("facing must be in [0, 3]: " + facing);
        }
        return facing;
    }

    private static int wallSide(int value) {
        if (value < 0 || value > 2) {
            throw new IllegalArgumentException("wall side must be 0, 1 or 2: " + value);
        }
        return value;
    }
}
