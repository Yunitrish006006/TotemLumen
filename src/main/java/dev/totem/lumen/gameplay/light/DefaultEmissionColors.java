package dev.totem.lumen.gameplay.light;

/** Shared vanilla fallback colors used by both gameplay lighting and the client material resolver. */
public final class DefaultEmissionColors {
    private static final EmissionColor SOUL = new EmissionColor(0.28f, 0.78f, 1.0f);
    private static final EmissionColor REDSTONE = new EmissionColor(1.0f, 0.18f, 0.06f);
    private static final EmissionColor LAVA = new EmissionColor(1.0f, 0.32f, 0.08f);
    private static final EmissionColor OCHRE_FROGLIGHT = new EmissionColor(1.0f, 0.76f, 0.35f);
    private static final EmissionColor VERDANT_FROGLIGHT = new EmissionColor(0.58f, 1.0f, 0.62f);
    private static final EmissionColor PEARLESCENT_FROGLIGHT = new EmissionColor(1.0f, 0.62f, 0.92f);
    private static final EmissionColor SEA = new EmissionColor(0.62f, 0.90f, 1.0f);
    private static final EmissionColor END_ROD = new EmissionColor(0.88f, 0.84f, 1.0f);
    private static final EmissionColor GLOWSTONE = new EmissionColor(1.0f, 0.78f, 0.42f);
    private static final EmissionColor SHROOMLIGHT = new EmissionColor(1.0f, 0.48f, 0.18f);
    private static final EmissionColor WARM = new EmissionColor(1.0f, 0.55f, 0.22f);
    private static final EmissionColor FALLBACK = new EmissionColor(1.0f, 0.86f, 0.66f);

    private DefaultEmissionColors() {
    }

    public static EmissionColor forBlock(String sourceId, int emission) {
        if (emission <= 0) {
            return EmissionColor.BLACK;
        }
        if (sourceId.contains("soul_")) return SOUL;
        if (sourceId.contains("redstone_torch")) return REDSTONE;
        if (sourceId.contains("lava") || sourceId.contains("magma")) return LAVA;
        if (sourceId.contains("ochre_froglight")) return OCHRE_FROGLIGHT;
        if (sourceId.contains("verdant_froglight")) return VERDANT_FROGLIGHT;
        if (sourceId.contains("pearlescent_froglight")) return PEARLESCENT_FROGLIGHT;
        if (sourceId.contains("sea_lantern") || sourceId.contains("conduit")) return SEA;
        if (sourceId.contains("end_rod")) return END_ROD;
        if (sourceId.contains("glowstone")) return GLOWSTONE;
        if (sourceId.contains("shroomlight")) return SHROOMLIGHT;
        if (sourceId.contains("fire")
                || sourceId.contains("torch")
                || sourceId.contains("lantern")
                || sourceId.contains("campfire")
                || sourceId.contains("jack_o_lantern")) {
            return WARM;
        }
        return FALLBACK;
    }
}
