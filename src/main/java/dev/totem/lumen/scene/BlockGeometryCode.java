package dev.totem.lumen.scene;

/**
 * Compact block-local geometry identifiers packed into the high 16 bits of each GPU voxel word.
 *
 * <p>Code zero intentionally means the legacy/full-cube fast path. P14B reserves the upper nibble
 * as a geometry family and the low 12 bits as family-specific parameters. P15 reuses spare family
 * and pane parameter bits for compact glass transmission metadata without growing the voxel word.
 * P16 adds a full-cube surface family carrying quantized roughness/metallic response. P14C reserves
 * one family for a deduplicated generic block-model mesh id, keeping the same 32-bit voxel ABI.</p>
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
    public static final int FENCE_GATE = 0x7000;
    public static final int GLASS_CUBE = 0x8000;
    public static final int SURFACE_CUBE = 0x9000;
    public static final int MODEL_MESH = 0xA000;
    public static final int TEXTURED_CUBE = 0xB000;

    public static final int NORTH = 0;
    public static final int EAST = 1;
    public static final int SOUTH = 2;
    public static final int WEST = 3;

    public static final int CONNECT_NORTH = 1;
    public static final int CONNECT_EAST = 1 << 1;
    public static final int CONNECT_SOUTH = 1 << 2;
    public static final int CONNECT_WEST = 1 << 3;

    /** P15 pane parameter bit: same PANE geometry, but ray transport may continue through it. */
    public static final int PANE_TRANSMISSIVE = 1 << 4;
    public static final int TRANSMISSION_TINT_SHIFT = 5;
    public static final int TRANSMISSION_TINT_MASK = 0x1F << TRANSMISSION_TINT_SHIFT;

    /** P16 surface-cube parameters: 4-bit roughness followed by 4-bit metallic response. */
    public static final int SURFACE_ROUGHNESS_MASK = 0xF;
    public static final int SURFACE_METALLIC_SHIFT = 4;
    public static final int SURFACE_METALLIC_MASK = 0xF << SURFACE_METALLIC_SHIFT;

    public static final int TINT_CLEAR = 0;
    public static final int TINT_WHITE = 1;
    public static final int TINT_ORANGE = 2;
    public static final int TINT_MAGENTA = 3;
    public static final int TINT_LIGHT_BLUE = 4;
    public static final int TINT_YELLOW = 5;
    public static final int TINT_LIME = 6;
    public static final int TINT_PINK = 7;
    public static final int TINT_GRAY = 8;
    public static final int TINT_LIGHT_GRAY = 9;
    public static final int TINT_CYAN = 10;
    public static final int TINT_PURPLE = 11;
    public static final int TINT_BLUE = 12;
    public static final int TINT_BROWN = 13;
    public static final int TINT_GREEN = 14;
    public static final int TINT_RED = 15;
    public static final int TINT_BLACK = 16;

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
    public static final int IN_WALL = 1 << 3;

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

    /** Packs four wall-side states (0 none, 1 low, 2 tall) plus the center-post bit. */
    public static int wall(int north, int east, int south, int west, boolean up) {
        return WALL
                | wallSide(north)
                | (wallSide(east) << 2)
                | (wallSide(south) << 4)
                | (wallSide(west) << 6)
                | (up ? WALL_UP : 0);
    }

    public static int pane(int connections) {
        return PANE | (connections & 0xF);
    }

    public static int transmissivePane(int connections, int tint) {
        return PANE
                | (connections & 0xF)
                | PANE_TRANSMISSIVE
                | transmissionTintBits(tint);
    }

    public static int glassCube(int tint) {
        return GLASS_CUBE | (tint & 0x1F);
    }

    /** Full-cube fast path with P16 optical response encoded in spare geometry bits. */
    public static int surfaceCube(float roughness, float metallic) {
        return SURFACE_CUBE
                | quantizeNormalized(roughness, "roughness")
                | (quantizeNormalized(metallic, "metallic") << SURFACE_METALLIC_SHIFT);
    }

    /** P18 textured full-cube AABB fast path using a six-face surface-set id. */
    public static int texturedCube(int surfaceSetId) {
        if (surfaceSetId <= 0 || surfaceSetId > PARAM_MASK) {
            throw new IllegalArgumentException(
                    "textured cube surface-set id must be in [1, 4095]: " + surfaceSetId
            );
        }
        return TEXTURED_CUBE | surfaceSetId;
    }

    public static int texturedCubeSurfaceSetId(int geometryCode) {
        if (family(geometryCode) != TEXTURED_CUBE) {
            throw new IllegalArgumentException(
                    "geometry code is not a P18 textured cube: " + geometryCode
            );
        }
        return geometryCode & PARAM_MASK;
    }

    /** P14C generic block-model mesh. Mesh id zero intentionally means no static model geometry. */
    public static int modelMesh(int meshId) {
        if (meshId < 0 || meshId > PARAM_MASK) {
            throw new IllegalArgumentException("model mesh id must be in [0, 4095]: " + meshId);
        }
        return MODEL_MESH | meshId;
    }

    public static int modelMeshId(int geometryCode) {
        if (family(geometryCode) != MODEL_MESH) {
            throw new IllegalArgumentException("geometry code is not a P14C model mesh: " + geometryCode);
        }
        return geometryCode & PARAM_MASK;
    }

    public static float surfaceRoughness(int geometryCode) {
        requireSurfaceCube(geometryCode);
        return (geometryCode & SURFACE_ROUGHNESS_MASK) / 15.0f;
    }

    public static float surfaceMetallic(int geometryCode) {
        requireSurfaceCube(geometryCode);
        return ((geometryCode & SURFACE_METALLIC_MASK) >> SURFACE_METALLIC_SHIFT) / 15.0f;
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

    public static int fenceGate(int facing, boolean open, boolean inWall) {
        return FENCE_GATE
                | directionBits(facing)
                | (open ? OPEN : 0)
                | (inWall ? IN_WALL : 0);
    }

    public static int family(int geometryCode) {
        return geometryCode & FAMILY_MASK;
    }

    public static int transmissionTint(int geometryCode) {
        if (family(geometryCode) == GLASS_CUBE) {
            return geometryCode & 0x1F;
        }
        if (family(geometryCode) == PANE && (geometryCode & PANE_TRANSMISSIVE) != 0) {
            return (geometryCode & TRANSMISSION_TINT_MASK) >> TRANSMISSION_TINT_SHIFT;
        }
        return TINT_CLEAR;
    }

    public static boolean isKnown(int geometryCode) {
        if (geometryCode == FULL_CUBE || geometryCode == SLAB_BOTTOM || geometryCode == SLAB_TOP) {
            return true;
        }
        return switch (family(geometryCode)) {
            case STAIRS, FENCE, WALL, PANE, DOOR, TRAPDOOR, FENCE_GATE,
                    GLASS_CUBE, SURFACE_CUBE, MODEL_MESH, TEXTURED_CUBE -> true;
            default -> false;
        };
    }

    private static int transmissionTintBits(int tint) {
        if (tint < 0 || tint > 31) {
            throw new IllegalArgumentException("transmission tint must be in [0, 31]: " + tint);
        }
        return (tint & 0x1F) << TRANSMISSION_TINT_SHIFT;
    }

    private static int quantizeNormalized(float value, String field) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(field + " must be a finite value in [0, 1]");
        }
        return Math.round(value * 15.0f) & 0xF;
    }

    private static void requireSurfaceCube(int geometryCode) {
        if (family(geometryCode) != SURFACE_CUBE) {
            throw new IllegalArgumentException("geometry code is not a P16 surface cube: " + geometryCode);
        }
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
