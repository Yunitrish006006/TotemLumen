package dev.totem.lumen.integration;

import dev.totem.lumen.scene.FluidGeometrySnapshot;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe active-level cache populated from Minecraft's section-meshing workers. */
public final class FluidRenderGeometryCache {
    private static final ConcurrentHashMap<Long, FluidGeometrySnapshot> SNAPSHOTS = new ConcurrentHashMap<>();
    private static final AtomicLong REVISION = new AtomicLong();

    private static volatile String activeDimensionId;

    private FluidRenderGeometryCache() {
    }

    public static void setActiveDimension(String dimensionId) {
        Objects.requireNonNull(dimensionId, "dimensionId");
        String previous = activeDimensionId;
        if (dimensionId.equals(previous)) return;
        synchronized (FluidRenderGeometryCache.class) {
            previous = activeDimensionId;
            if (dimensionId.equals(previous)) return;
            SNAPSHOTS.clear();
            activeDimensionId = dimensionId;
            REVISION.incrementAndGet();
        }
    }

    public static String activeDimensionId() {
        return activeDimensionId;
    }

    static void store(FluidGeometrySnapshot snapshot) {
        if (snapshot == null || !snapshot.dimensionId().equals(activeDimensionId)) return;
        long key = BlockPos.asLong(snapshot.blockX(), snapshot.blockY(), snapshot.blockZ());
        SNAPSHOTS.compute(key, (ignored, previous) -> {
            if (previous != null && previous.geometryEquals(snapshot)) return previous;
            REVISION.incrementAndGet();
            return snapshot;
        });
    }

    /**
     * Fluid corner heights and side visibility depend on neighbors, so a block update invalidates
     * a bounded 3x3x3 neighborhood until Minecraft re-tessellates the affected section(s).
     */
    public static void invalidateNeighborhood(BlockPos center) {
        boolean changed = false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long key = BlockPos.asLong(
                            center.getX() + dx,
                            center.getY() + dy,
                            center.getZ() + dz
                    );
                    changed |= SNAPSHOTS.remove(key) != null;
                }
            }
        }
        if (changed) REVISION.incrementAndGet();
    }

    public static List<FluidGeometrySnapshot> snapshots() {
        ArrayList<FluidGeometrySnapshot> result = new ArrayList<>(SNAPSHOTS.values());
        result.sort(Comparator
                .comparingInt(FluidGeometrySnapshot::sectionX)
                .thenComparingInt(FluidGeometrySnapshot::sectionY)
                .thenComparingInt(FluidGeometrySnapshot::sectionZ)
                .thenComparingInt(FluidGeometrySnapshot::blockX)
                .thenComparingInt(FluidGeometrySnapshot::blockY)
                .thenComparingInt(FluidGeometrySnapshot::blockZ));
        return List.copyOf(result);
    }

    public static long revision() {
        return REVISION.get();
    }

    public static void clear() {
        synchronized (FluidRenderGeometryCache.class) {
            if (!SNAPSHOTS.isEmpty() || activeDimensionId != null) {
                SNAPSHOTS.clear();
                activeDimensionId = null;
                REVISION.incrementAndGet();
            }
        }
    }
}
