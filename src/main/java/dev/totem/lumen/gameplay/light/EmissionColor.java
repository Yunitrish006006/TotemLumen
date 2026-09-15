package dev.totem.lumen.gameplay.light;

/** Normalized linear RGB used as the shared fallback tint for emissive blocks. */
public record EmissionColor(float red, float green, float blue) {
    public static final EmissionColor BLACK = new EmissionColor(0.0f, 0.0f, 0.0f);

    public EmissionColor {
        if (!normalized(red) || !normalized(green) || !normalized(blue)) {
            throw new IllegalArgumentException("emission color components must be finite values in [0, 1]");
        }
    }

    private static boolean normalized(float value) {
        return Float.isFinite(value) && value >= 0.0f && value <= 1.0f;
    }
}
