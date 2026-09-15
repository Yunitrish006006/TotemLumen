package dev.totem.lumen.scene;

/**
 * Compact block-local geometry identifiers packed into the high 16 bits of each GPU voxel word.
 *
 * <p>Code zero intentionally means the legacy/full-cube fast path so existing snapshots remain
 * valid. More complex block-local primitive sets can be added without changing the 32-bit voxel
 * ABI.</p>
 */
public final class BlockGeometryCode {
    public static final int FULL_CUBE = 0;
    public static final int SLAB_BOTTOM = 1;
    public static final int SLAB_TOP = 2;

    private BlockGeometryCode() {
    }

    public static boolean isKnown(int geometryCode) {
        return geometryCode == FULL_CUBE
                || geometryCode == SLAB_BOTTOM
                || geometryCode == SLAB_TOP;
    }
}
