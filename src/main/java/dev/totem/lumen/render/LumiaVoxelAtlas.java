package dev.totem.lumen.render;

import java.util.Arrays;

/** Maps the moving scene window to fixed GPU tiles indexed by absolute section coordinates. */
public final class LumiaVoxelAtlas {
    @FunctionalInterface
    public interface Upload {
        void write(int tile, RasterLightingVolume.Section section);
    }

    private final RasterLightingVolume.Section[] resident = new RasterLightingVolume.Section[RasterLightingVolume.SLOTS];
    private final int[] mapping = new int[RasterLightingVolume.SLOTS];
    private RasterLightingVolume complete;

    /** Zero represents missing data; each positive entry is a physical tile plus one. */
    public int entry(int slot) { return mapping[slot]; }

    public boolean update(RasterLightingVolume volume, Upload upload) {
        if (volume == complete) return false;
        int remaining = RasterLightingWindow.UPLOADS_PER_FRAME;
        boolean pending = false;
        boolean changed = false;
        int side = RasterLightingVolume.SECTIONS;
        for (int slot = 0; slot < mapping.length; slot++) {
            int sx = (volume.x >> 4) + slot % side;
            int sz = (volume.z >> 4) + slot / side % side;
            int sy = (volume.y >> 4) + slot / (side * side);
            int tile = RasterLightingVolume.slot(Math.floorMod(sx, side), Math.floorMod(sy, side), Math.floorMod(sz, side));
            var section = volume.section(slot);
            if (section != null && section != resident[tile]) {
                if (remaining > 0) {
                    upload.write(tile, section);
                    resident[tile] = section;
                    remaining--;
                } else {
                    pending = true;
                }
            }
            int entry = section != null && section == resident[tile] ? tile + 1 : 0;
            changed |= mapping[slot] != entry;
            mapping[slot] = entry;
        }
        complete = pending ? null : volume;
        return changed;
    }

    public void clear() {
        Arrays.fill(resident, null);
        Arrays.fill(mapping, 0);
        complete = null;
    }
}
