package dev.totem.lumen.material;

/**
 * Canonical LabPBR 1.3 channel decoding shared by CPU validation and the later GPU implementation.
 *
 * <p>All input channels are raw unsigned 8-bit values from the resource-pack PNG. LabPBR auxiliary
 * textures are linear data and must not be sRGB-decoded before these rules are applied.</p>
 */
public final class LabPbr13Decoder {
    public static final int DIELECTRIC_MAX = 229;
    public static final int METAL_MIN = 230;
    public static final int METAL_MAX = 254;
    public static final int CUSTOM_METAL = 255;

    private LabPbr13Decoder() {
    }

    public static NormalSample decodeNormal(int red, int green, int blue, int alpha) {
        checkByte(red);
        checkByte(green);
        checkByte(blue);
        checkByte(alpha);

        float x = red / 255.0f * 2.0f - 1.0f;
        // LabPBR normal maps use DirectX/Y-down convention. Totem's tangent basis uses +Y,
        // therefore flip the encoded Y component while decoding.
        float y = 1.0f - green / 255.0f * 2.0f;
        float zSquared = Math.max(0.0f, 1.0f - x * x - y * y);
        float z = (float) Math.sqrt(zSquared);
        return new NormalSample(x, y, z, blue / 255.0f, alpha / 255.0f);
    }

    public static SpecularSample decodeSpecular(int red, int green, int blue, int alpha) {
        checkByte(red);
        checkByte(green);
        checkByte(blue);
        checkByte(alpha);

        float smoothness = red / 255.0f;
        float oneMinusSmoothness = 1.0f - smoothness;
        float roughness = oneMinusSmoothness * oneMinusSmoothness;

        boolean metal = green >= METAL_MIN;
        // LabPBR 1.3 stores dielectric F0 linearly in the raw green channel.
        // Values 0..229 therefore map directly to 0/255..229/255.
        float dielectricF0 = metal ? 0.0f : green / 255.0f;
        int metalCode = metal ? green : 0;

        float porosity = blue <= 64 ? blue / 64.0f : 0.0f;
        float subsurface = blue >= 65 ? (blue - 65) / 190.0f : 0.0f;

        // LabPBR reserves alpha=255 as "no emission". Alpha=0 is also zero energy.
        float emission = alpha >= 255 ? 0.0f : alpha / 254.0f;

        return new SpecularSample(
                roughness,
                dielectricF0,
                metalCode,
                porosity,
                subsurface,
                emission
        );
    }

    public static boolean isHardcodedMetal(int code) {
        return code >= 230 && code <= 237;
    }

    public static boolean isCustomMetal(int code) {
        return code == CUSTOM_METAL || (code >= 238 && code <= 254);
    }

    private static void checkByte(int value) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException("LabPBR channel must be in [0,255]: " + value);
        }
    }

    public record NormalSample(
            float normalX,
            float normalY,
            float normalZ,
            float ambientOcclusion,
            float height
    ) {
    }

    public record SpecularSample(
            float roughness,
            float dielectricF0,
            int metalCode,
            float porosity,
            float subsurface,
            float emission
    ) {
        public boolean metallic() {
            return metalCode >= METAL_MIN;
        }
    }
}
