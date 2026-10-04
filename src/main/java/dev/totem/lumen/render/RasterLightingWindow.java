package dev.totem.lumen.render;

import java.util.BitSet;

/** Client-thread-owned rolling cache. Published volumes retain only immutable section payloads. */
public final class RasterLightingWindow {
    public static final int SECTIONS_PER_TICK = 2;
    public static final int UPLOADS_PER_FRAME = 8;
    private final RasterLightingVolume.Section[] sections = new RasterLightingVolume.Section[RasterLightingVolume.SLOTS];
    private final BitSet dirty = new BitSet(RasterLightingVolume.SLOTS);
    private final int[] nearest = nearestOrder();
    private final int x, y, z;
    private final long epoch;
    private int cursor, urgentStreak;
    private RasterLightingVolume published;

    public RasterLightingWindow(int x, int y, int z, long epoch, RasterLightingWindow previous) {
        if ((x & 15) != 0 || (y & 15) != 0 || (z & 15) != 0)
            throw new IllegalArgumentException("Section-aligned origins required");
        this.x = x; this.y = y; this.z = z; this.epoch = epoch;
        for (int slot = 0; slot < sections.length; slot++) {
            int oldSlot = previous == null ? -1 : previous.slotAt(sectionX(slot), sectionY(slot), sectionZ(slot));
            if (oldSlot >= 0) sections[slot] = previous.sections[oldSlot];
            if (sections[slot] == null || (oldSlot >= 0 && previous.dirty.get(oldSlot))) dirty.set(slot);
        }
    }

    public boolean at(int ox, int oy, int oz) { return x == ox && y == oy && z == oz; }
    public int sectionX(int slot) { return (x >> 4) + slot % RasterLightingVolume.SECTIONS; }
    public int sectionY(int slot) { return (y >> 4) + slot / (RasterLightingVolume.SECTIONS * RasterLightingVolume.SECTIONS); }
    public int sectionZ(int slot) { return (z >> 4) + (slot / RasterLightingVolume.SECTIONS) % RasterLightingVolume.SECTIONS; }
    public RasterLightingVolume.Section section(int slot) { return sections[slot]; }
    public int retainedSections() {
        int count = 0;
        for (var section : sections) if (section != null) count++;
        return count;
    }

    /** Coordinates are absolute section coordinates. No unbounded event queue or duplicate entries. */
    public boolean invalidate(int sx, int sy, int sz) {
        int slot = slotAt(sx, sy, sz);
        if (slot < 0) return false;
        if (sections[slot] != null) published = null;
        sections[slot] = null;
        dirty.set(slot);
        return true;
    }

    public void put(int slot, RasterLightingVolume.Section section) {
        if (section == null) throw new IllegalArgumentException("Captured section required");
        if (sections[slot] != section) published = null;
        sections[slot] = section;
        dirty.clear(slot);
    }

    /** Three urgent captures then one refresh: repeated edits cannot starve resident data. */
    public int next() {
        if (!dirty.isEmpty() && urgentStreak < 3) {
            urgentStreak++;
            for (int slot : nearest) if (dirty.get(slot)) return slot;
        }
        urgentStreak = 0;
        int slot = nearest[cursor];
        cursor = (cursor + 1) % nearest.length;
        return slot;
    }

    public RasterLightingVolume snapshot() {
        // Only this client-thread-owned window mutates. Readers retain immutable cloned slots;
        // an unchanged refresh must not invalidate the render thread's volume-identity cache.
        if (published == null) published = new RasterLightingVolume(x, y, z, epoch, sections);
        return published;
    }

    private int slotAt(int sx, int sy, int sz) {
        long dx = (long) sx - (x >> 4), dy = (long) sy - (y >> 4), dz = (long) sz - (z >> 4);
        int side = RasterLightingVolume.SECTIONS;
        if (dx < 0 || dy < 0 || dz < 0 || dx >= side || dy >= side || dz >= side) return -1;
        return RasterLightingVolume.slot((int) dx, (int) dy, (int) dz);
    }

    private static int[] nearestOrder() {
        // Centre-first acquisition; never allocate/sort a world-size collection.
        return java.util.stream.IntStream.range(0, RasterLightingVolume.SLOTS).boxed()
                .sorted(java.util.Comparator.comparingInt(RasterLightingWindow::distanceSquared))
                .mapToInt(Integer::intValue).toArray();
    }

    private static int distanceSquared(int slot) {
        int side = RasterLightingVolume.SECTIONS;
        int dx = slot % side - side / 2;
        int dy = slot / (side * side) - side / 2;
        int dz = (slot / side) % side - side / 2;
        return dx * dx + dy * dy + dz * dz;
    }
}
