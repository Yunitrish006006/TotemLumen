package dev.totem.lumen.gameplay.light;

/** Compact vanilla-range block coordinate used internally by the propagation queue. */
final class PackedServerPos {
    private static final long XZ_MASK = 0x3FFFFFFL;
    private static final long Y_MASK = 0xFFFL;

    private PackedServerPos() {
    }

    static long pack(int x, int y, int z) {
        return ((long) x & XZ_MASK) << 38
                | ((long) z & XZ_MASK) << 12
                | ((long) y & Y_MASK);
    }

    static int x(long packed) {
        return (int) (packed >> 38);
    }

    static int y(long packed) {
        return (int) (packed << 52 >> 52);
    }

    static int z(long packed) {
        return (int) (packed << 26 >> 38);
    }
}
