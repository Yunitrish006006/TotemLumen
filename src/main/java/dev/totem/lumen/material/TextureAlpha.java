package dev.totem.lumen.material;

/**
 * Canonical P18 albedo-alpha semantics used by CPU validation and mirrored by GPU shading.
 *
 * <p>Alpha is coverage for textured polygon surfaces. Zero-alpha texels are holes and must not
 * accept a ray hit. Intermediate alpha retains fractional coverage/transmission information; it is
 * not promoted to P15 glass volume semantics.</p>
 */
public final class TextureAlpha {
    private TextureAlpha() {
    }

    public static int alpha8(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    public static boolean acceptsOpaqueHit(int argb) {
        return alpha8(argb) == 255;
    }

    public static boolean isHole(int argb) {
        return alpha8(argb) == 0;
    }

    public static boolean isPartial(int argb) {
        int alpha = alpha8(argb);
        return alpha > 0 && alpha < 255;
    }

    public static float coverage(int argb) {
        return alpha8(argb) / 255.0f;
    }

    public static AlphaClass classify(int argb) {
        int alpha = alpha8(argb);
        if (alpha == 0) return AlphaClass.HOLE;
        if (alpha == 255) return AlphaClass.OPAQUE;
        return AlphaClass.PARTIAL;
    }

    public enum AlphaClass {
        HOLE,
        PARTIAL,
        OPAQUE
    }
}
