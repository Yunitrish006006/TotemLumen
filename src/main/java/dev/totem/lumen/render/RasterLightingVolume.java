package dev.totem.lumen.render;

import dev.totem.lumen.gameplay.light.PackedRgbLight;

/** Immutable section payloads for the experimental raster-primary renderer; no Minecraft classes. */
public final class RasterLightingVolume {
    public static final int SECTIONS = 6;
    public static final int SIDE = SECTIONS * 16;
    public static final int SLOTS = SECTIONS * SECTIONS * SECTIONS;
    public static final int ATLAS_WIDTH = 256;
    public static final int ATLAS_HEIGHT = ((SLOTS + 15) / 16) * 256;
    public static final int RAY_DISTANCE = 24;
    public static final int AIR = 0x80000000;

    /** Same source chroma/intensity as Minecraft RGB, without starting its propagation engine. */
    public static int packRgbSource(boolean solid, int source) {
        return packEmission(solid, PackedRgbLight.hueRed(source) / 15f,
                PackedRgbLight.hueGreen(source) / 15f, PackedRgbLight.hueBlue(source) / 15f,
                PackedRgbLight.alpha(source) / 15f);
    }

    /** RGB remains independent; alpha encodes known-air/solid, never light intensity. */
    public static int packEmission(boolean solid, float red, float green, float blue, float strength) {
        float intensity = Float.isFinite(strength) ? Math.clamp(strength, 0, 1) : 0;
        return ((solid ? 255 : 128) << 24) | (channel(blue * intensity) << 16)
                | (channel(green * intensity) << 8) | channel(red * intensity);
    }

    private static int channel(float value) {
        return Float.isFinite(value) ? Math.round(Math.clamp(value, 0, 1) * 255) : 0;
    }

    public static final class Section {
        private final int[] abgr;
        private final char[] materialIds;
        private final char[] surfaceSetIds;
        private final int[] emitters;
        public Section(int[] abgr) { this(abgr, new char[4096], null, false); }
        public Section(int[] abgr, char[] materialIds) { this(abgr, materialIds, null, false); }
        public Section(int[] abgr, char[] materialIds, char[] surfaceSetIds) {
            this(abgr, materialIds, surfaceSetIds, false);
        }
        public Section(int[] abgr, boolean collectEmitters) {
            this(abgr, new char[4096], null, collectEmitters);
        }
        public Section(int[] abgr, char[] materialIds, boolean collectEmitters) {
            this(abgr, materialIds, null, collectEmitters);
        }
        public Section(int[] abgr, char[] materialIds, char[] surfaceSetIds, boolean collectEmitters) {
            if (abgr.length != 4096 || materialIds.length != 4096
                    || (surfaceSetIds != null && surfaceSetIds.length != 4096))
                throw new IllegalArgumentException("Expected 16 cubed voxel/material/surface arrays");
            this.abgr = abgr.clone();
            this.materialIds = materialIds.clone();
            this.surfaceSetIds = surfaceSetIds == null ? new char[0] : surfaceSetIds.clone();
            emitters = new int[collectEmitters ? 8 : 0];
            java.util.Arrays.fill(emitters, -1);
            if (collectEmitters) for (int i = 0; i < 4096; i++) {
                if ((this.abgr[i] & 0xffffff) == 0 || (this.abgr[i] >>> 24) == 0) continue;
                int x = i & 15, y = i >>> 8, z = (i >>> 4) & 15;
                int octant = (x >>> 3) | ((z >>> 3) << 1) | ((y >>> 3) << 2);
                int old = emitters[octant];
                // One real emitter per octant; strongest, then closest to its centre, then index.
                if (old < 0 || energy(this.abgr[i]) > energy(this.abgr[old])
                        || (energy(this.abgr[i]) == energy(this.abgr[old]) && centreDistance(i) < centreDistance(old))) emitters[octant] = i;
            }
        }
        public int voxel(int index) { return abgr[index]; }
        public int materialId(int index) { return materialIds[index]; }
        public int surfaceSetId(int index) { return surfaceSetIds.length == 0 ? 0 : surfaceSetIds[index]; }
        public boolean hasSurfaceSetIds() { return surfaceSetIds.length != 0; }
        public int emitterCount() { return emitters.length; }
        public int emitter(int octant) { return emitters[octant]; }
        private static int energy(int v) { return Math.max(v & 255, Math.max((v >>> 8) & 255, (v >>> 16) & 255)); }
        private static int centreDistance(int i) {
            int x = (i & 7) * 2 - 7, y = ((i >>> 8) & 7) * 2 - 7, z = ((i >>> 4) & 7) * 2 - 7;
            return x*x + y*y + z*z;
        }
    }

    private final Section[] sections;
    public final int x, y, z;
    public final long epoch;

    public RasterLightingVolume(int x, int y, int z, long epoch, Section[] sections) {
        if (sections.length != SLOTS) throw new IllegalArgumentException("Wrong section count");
        this.x = x; this.y = y; this.z = z; this.epoch = epoch;
        this.sections = sections.clone();
    }

    public Section section(int index) { return sections[index]; }
    public static int origin(double coordinate) { return Math.floorDiv((int) Math.floor(coordinate), 16) * 16 - 48; }
    public static int slot(int x, int y, int z) { return (y * SECTIONS + z) * SECTIONS + x; }
    public static int tileX(int slot) { return (slot & 15) * 16; }
    public static int tileY(int slot) { return (slot >>> 4) * 256; }
    public static int index(int x, int y, int z) { return (y * 16 + z) * 16 + x; }
}
