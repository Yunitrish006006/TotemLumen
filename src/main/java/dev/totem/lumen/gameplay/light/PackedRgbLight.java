package dev.totem.lumen.gameplay.light;

/** RGBA gameplay light in one 16-bit value: straight RGB chroma plus light intensity A. */
public final class PackedRgbLight {
    public static final int MAX_CHANNEL = 15;
    public static final int MASK = 0xF;

    private PackedRgbLight() {
    }

    public static char pack(int red, int green, int blue) {
        int maximum = Math.max(clamp(red), Math.max(clamp(green), clamp(blue)));
        if (maximum == 0) {
            return 0;
        }
        return packRgba(
                Math.round(clamp(red) * 15.0f / maximum),
                Math.round(clamp(green) * 15.0f / maximum),
                Math.round(clamp(blue) * 15.0f / maximum),
                maximum
        );
    }

    /** Packs unmultiplied chroma and light intensity; A never changes texture opacity. */
    public static char packRgba(int red, int green, int blue, int alpha) {
        int boundedAlpha = clamp(alpha);
        if (boundedAlpha == 0 || (red <= 0 && green <= 0 && blue <= 0)) {
            return 0;
        }
        return (char) (clamp(red) | (clamp(green) << 4)
                | (clamp(blue) << 8) | (boundedAlpha << 12));
    }

    public static char fromNormalized(EmissionColor color, int strength) {
        int bounded = clamp(strength);
        float maximum = Math.max(color.red(), Math.max(color.green(), color.blue()));
        if (bounded == 0 || maximum <= 0.0f) {
            return 0;
        }
        return packRgba(
                Math.round(color.red() * 15.0f / maximum),
                Math.round(color.green() * 15.0f / maximum),
                Math.round(color.blue() * 15.0f / maximum),
                Math.round(maximum * bounded)
        );
    }

    public static int hueRed(int packed) {
        return packed & MASK;
    }

    public static int hueGreen(int packed) {
        return (packed >>> 4) & MASK;
    }

    public static int hueBlue(int packed) {
        return (packed >>> 8) & MASK;
    }

    public static int alpha(int packed) {
        return (packed >>> 12) & MASK;
    }

    /** Effective red light level after multiplying chroma by A. */
    public static int red(int packed) {
        return (hueRed(packed) * alpha(packed) + 7) / 15;
    }

    public static int green(int packed) {
        return (hueGreen(packed) * alpha(packed) + 7) / 15;
    }

    public static int blue(int packed) {
        return (hueBlue(packed) * alpha(packed) + 7) / 15;
    }

    public static int maxChannel(int packed) {
        return alpha(packed);
    }

    public static char componentMax(int left, int right) {
        if (left == 0) return (char) right;
        if (right == 0) return (char) left;
        int red = Math.max(red(left), red(right));
        int green = Math.max(green(left), green(right));
        int blue = Math.max(blue(left), blue(right));
        if (red == red(left) && green == green(left) && blue == blue(left)) {
            return (char) left;
        }
        if (red == red(right) && green == green(right) && blue == blue(right)) {
            return (char) right;
        }
        return pack(red, green, blue);
    }

    public static char attenuate(int packed, int amount) {
        int loss = Math.max(1, amount);
        int remaining = alpha(packed) - loss;
        if (remaining <= 0) {
            return 0;
        }
        return (char) ((packed & 0x0FFF) | (remaining << 12));
    }

    public static float effectiveBrightness(int packed, GameplayLightSensitivity sensitivity) {
        return Math.max(
                red(packed) * sensitivity.blockRed(),
                Math.max(
                        green(packed) * sensitivity.blockGreen(),
                        blue(packed) * sensitivity.blockBlue()
                )
        );
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(MAX_CHANNEL, value));
    }
}
