package dev.totem.lumen.material;

/**
 * GPU-friendly material feature bits. Flags are intentionally non-exclusive: a torch can be
 * CUTOUT + EMISSIVE, while a later fluid material can be TRANSLUCENT + TRANSMISSIVE + FLUID.
 */
public final class MaterialFlags {
    public static final int AIR = 1 << 0;
    public static final int OPAQUE = 1 << 1;
    public static final int EMISSIVE = 1 << 2;
    public static final int CUTOUT = 1 << 3;
    public static final int TRANSLUCENT = 1 << 4;
    public static final int FLUID = 1 << 5;
    public static final int TRANSMISSIVE = 1 << 6;

    private MaterialFlags() {
    }

    public static boolean has(int flags, int flag) {
        return (flags & flag) != 0;
    }
}
