package dev.totem.lumen.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LumiaGlassVoxelTest {
    @Test void connectedPanesCannotBecomePhantomEmitters() {
        int[] cells = new int[4096];
        for (int dye = 0; dye < 17; dye++) cells[dye] = ((80 + dye) << 24) | 15;
        var section = new RasterLightingVolume.Section(cells, true);
        for (int octant = 0; octant < section.emitterCount(); octant++) assertEquals(-1, section.emitter(octant));
        cells[3] = (146 << 24) | 0x55aa33;
        section = new RasterLightingVolume.Section(cells, true);
        assertEquals(3, section.emitter(0));
        assertEquals(0x55aa33, section.voxel(3) & 0xffffff);
    }
}
