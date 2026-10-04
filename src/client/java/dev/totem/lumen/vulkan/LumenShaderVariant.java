package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Process-fixed opt-in; changing variants requires a restart, never a live pipeline swap. */
final class LumenShaderVariant {
    static final String PROPERTY = "totem.lumen.shaderVariant";
    static final String BASELINE = "baseline";
    private static final String SELECTED = validate(System.getProperty(PROPERTY, BASELINE));

    static String validate(String variant) {
        if (!BASELINE.equals(variant) && !FilteredTraceShaderPatch.VARIANT.equals(variant))
            throw new IllegalArgumentException("Unknown Lumen shader variant: " + variant);
        return variant;
    }

    static String select(String variant, String source) {
        return switch (validate(variant)) {
            case BASELINE -> source;
            case FilteredTraceShaderPatch.VARIANT -> FilteredTraceShaderPatch.apply(source);
            default -> throw new AssertionError("Validated variant missing");
        };
    }

    static String runtimeSource(String pass, String baseline) {
        String source = select(SELECTED, baseline);
        TotemLumenClient.LOGGER.info("Lumen shader selection: pass={}, variant={}, sourceSha256={}",
                pass, SELECTED, hash(source));
        return source;
    }

    static String hash(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
