package dev.totem.lumen.geometry;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Deduplicated six-face texture sets for canonical full cubes.
 *
 * <p>Face order is -X, +X, -Y, +Y, -Z, +Z. ID zero is reserved for an unknown/untextured set.
 * P18A populates this registry without changing the current voxel geometry code; P18B can later
 * reference the stable 12-bit id while retaining the full-cube AABB fast path.</p>
 */
public final class BlockSurfaceSetRegistry {
    public static final int MAX_SURFACE_SET_ID = 0x0FFF;

    private static final Map<CubeSurfaceSet, Integer> IDS = new HashMap<>();
    private static final Map<Integer, CubeSurfaceSet> SETS = new HashMap<>();
    private static int nextId = 1;
    private static long revision;

    private BlockSurfaceSetRegistry() {
    }

    public static synchronized int register(CubeSurfaceSet set) {
        Integer existing = IDS.get(set);
        if (existing != null) return existing;
        if (nextId > MAX_SURFACE_SET_ID) return -1;

        int id = nextId++;
        IDS.put(set, id);
        SETS.put(id, set);
        revision++;
        return id;
    }

    public static synchronized CubeSurfaceSet surfaceSet(int id) {
        return SETS.get(id);
    }

    public static synchronized int size() {
        return SETS.size();
    }

    public static synchronized long revision() {
        return revision;
    }

    public record CubeSurfaceSet(
            QuadSurface negativeX,
            QuadSurface positiveX,
            QuadSurface negativeY,
            QuadSurface positiveY,
            QuadSurface negativeZ,
            QuadSurface positiveZ
    ) {
        public CubeSurfaceSet {
            if (negativeX == null
                    || positiveX == null
                    || negativeY == null
                    || positiveY == null
                    || negativeZ == null
                    || positiveZ == null) {
                throw new IllegalArgumentException("cube surface set cannot contain null faces");
            }
        }

        public QuadSurface face(int faceIndex) {
            return switch (faceIndex) {
                case 0 -> negativeX;
                case 1 -> positiveX;
                case 2 -> negativeY;
                case 3 -> positiveY;
                case 4 -> negativeZ;
                case 5 -> positiveZ;
                default -> throw new IndexOutOfBoundsException("faceIndex=" + faceIndex);
            };
        }

        public QuadSurface[] copyFaces() {
            return new QuadSurface[]{
                    negativeX, positiveX,
                    negativeY, positiveY,
                    negativeZ, positiveZ
            };
        }
    }
}
