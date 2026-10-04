package dev.totem.lumen.render;

import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Diagnostic point-emitter selection. At most 216*8 candidates; no world references. */
public final class RasterDirectLights {
    public static final int MAX_LIGHTS = 32;
    public static final int TEXELS = MAX_LIGHTS * 2;
    private static final Comparator<Light> POSITION_ORDER = Comparator.comparingInt(Light::y)
            .thenComparingInt(Light::z).thenComparingInt(Light::x);
    public record Light(int x, int y, int z, int abgr) {
        public int positionTexel() { return 0xff000000 | x | (y << 8) | (z << 16); }
    }
    private RasterDirectLights() { }

    /** Camera-local distance selects membership; coordinate order stabilizes payload and shader ties. */
    public static List<Light> select(RasterLightingVolume volume, double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return List.of();
        Comparator<Light> order = Comparator.comparingDouble((Light l) -> distanceSquared(l, x, y, z))
                .thenComparing(POSITION_ORDER);
        var nearest = new PriorityQueue<Light>(MAX_LIGHTS, order.reversed());
        for (int slot = 0; slot < RasterLightingVolume.SLOTS; slot++) {
            var section = volume.section(slot);
            if (section == null) continue;
            for (int octant = 0; octant < section.emitterCount(); octant++) {
                int i = section.emitter(octant);
                if (i < 0) continue;
                var light = new Light((slot % 6) * 16 + (i & 15), (slot / 36) * 16 + (i >>> 8),
                        ((slot / 6) % 6) * 16 + ((i >>> 4) & 15), section.voxel(i));
                // A source farther away cannot reach a shaded surface in the 24-block near field.
                // Include one block for camera-cell quantisation, in addition to 24 + 12.5.
                if (distanceSquared(light, x, y, z) > 37.5 * 37.5) continue;
                if (nearest.size() < MAX_LIGHTS) nearest.add(light);
                else if (order.compare(light, nearest.peek()) < 0) { nearest.remove(); nearest.add(light); }
            }
        }
        // Reordering the same candidates as the camera moves causes needless GPU uploads and
        // changes which equal-contribution lights win the shader's bounded top-four selection.
        return nearest.stream().sorted(POSITION_ORDER).toList();
    }

    private static double distanceSquared(Light l, double x, double y, double z) {
        double dx = l.x + .5 - x, dy = l.y + .5 - y, dz = l.z + .5 - z;
        return dx*dx + dy*dy + dz*dz;
    }
}
