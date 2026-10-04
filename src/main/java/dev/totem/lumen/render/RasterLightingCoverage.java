package dev.totem.lumen.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Planned clipmap ABI for the independent-lighting stage. Does NOT enable far lighting yet.
 * Each level keeps the same cell count; doubling cell size grows coverage, not allocations cubically.
 */
public final class RasterLightingCoverage {
    public static final int MAX_VIEW_CHUNKS = 32;
    public static final int MAX_LEVELS = 6;
    private RasterLightingCoverage() { }

    public record Level(int cellSize, int guaranteedRadius) { }

    public static List<Level> forViewDistance(int chunks) {
        if (chunks < 1 || chunks > MAX_VIEW_CHUNKS) throw new IllegalArgumentException("Unsupported view distance");
        // Cover the square chunk view, not just its axial radius. Extra chunk covers camera placement.
        int requiredRadius = (int) Math.ceil(Math.sqrt(2) * (chunks + 1) * 16);
        List<Level> levels = new ArrayList<>();
        for (int i = 0; i < MAX_LEVELS; i++) {
            int size = 1 << i;
            int radius = RasterLightingVolume.RAY_DISTANCE * size;
            levels.add(new Level(size, radius));
            if (radius >= requiredRadius) return List.copyOf(levels);
        }
        throw new IllegalStateException("Coverage budget insufficient");
    }

    /** Raw RGBA voxel atlas budget only; excludes history, scene colour and driver allocations. */
    public static long atlasBytes(int chunks) {
        return (long) forViewDistance(chunks).size() * RasterLightingVolume.ATLAS_WIDTH
                * RasterLightingVolume.ATLAS_HEIGHT * 4;
    }
}
