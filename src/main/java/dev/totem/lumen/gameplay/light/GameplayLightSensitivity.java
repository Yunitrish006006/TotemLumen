package dev.totem.lumen.gameplay.light;

/** Per-entity spectral response used only for server-side gameplay decisions. */
public record GameplayLightSensitivity(
        float blockRed,
        float blockGreen,
        float blockBlue,
        float environmentRed,
        float environmentGreen,
        float environmentBlue
) {
    public static final GameplayLightSensitivity DEFAULT = new GameplayLightSensitivity(
            1.0f, 1.0f, 1.0f,
            1.0f, 1.0f, 1.0f
    );

    public GameplayLightSensitivity {
        validate(blockRed, "blockRed");
        validate(blockGreen, "blockGreen");
        validate(blockBlue, "blockBlue");
        validate(environmentRed, "environmentRed");
        validate(environmentGreen, "environmentGreen");
        validate(environmentBlue, "environmentBlue");
    }

    public float environmentBrightness(int packedEnvironment) {
        return Math.max(
                PackedRgbLight.red(packedEnvironment) * environmentRed,
                Math.max(
                        PackedRgbLight.green(packedEnvironment) * environmentGreen,
                        PackedRgbLight.blue(packedEnvironment) * environmentBlue
                )
        );
    }

    private static void validate(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be finite and in [0, 1]");
        }
    }
}
