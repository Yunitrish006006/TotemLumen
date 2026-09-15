package dev.totem.lumen.gameplay.light;

/** 12-bit RGB gameplay light packed into a 16-bit value. Each channel is 0..15. */
public final class PackedRgbLight {
    public static final int MAX_CHANNEL = 15;
    public static final int MASK = 0xF;

    private PackedRgbLight() {
    }

    public static char pack(int red, int green, int blue) {
        return (char) (clamp(red) | (clamp(green) << 4) | (clamp(blue) << 8));
    }

    public static char fromNormalized(EmissionColor color, int strength) {
        int bounded = clamp(strength);
        return pack(
                Math.round(color.red() * bounded),
                Math.round(color.green() * bounded),
                Math.round(color.blue() * bounded)
        );
    }

    public static int red(int packed) {
        return packed & MASK;
    }

    public static int green(int packed) {
        return (packed >>> 4) & MASK;
    }

    public static int blue(int packed) {
        return (packed >>> 8) & MASK;
    }

    public static int maxChannel(int packed) {
        return Math.max(red(packed), Math.max(green(packed), blue(packed)));
    }

    public static char componentMax(int left, int right) {
        return pack(
                Math.max(red(left), red(right)),
                Math.max(green(left), green(right)),
                Math.max(blue(left), blue(right))
        );
    }

    public static char attenuate(int packed, int amount) {
        int loss = Math.max(1, amount);
        return pack(
                Math.max(0, red(packed) - loss),
                Math.max(0, green(packed) - loss),
                Math.max(0, blue(packed) - loss)
        );
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
