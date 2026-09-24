package dev.totem.lumen.render;

import dev.totem.lumen.TotemLumenClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/** Persistent client-side renderer quality settings. */
public final class RendererSettings {
    public enum RenderProfile {
        MINECRAFT_PURE,
        MINECRAFT_RGB,
        TOTEM_LUMEN;

        public RenderProfile next() {
            RenderProfile[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum GiQuality {
        LOW(1),
        BALANCED(2),
        HIGH(3);

        private final int samples;

        GiQuality(int samples) {
            this.samples = samples;
        }

        public int samples() {
            return samples;
        }

        public GiQuality next() {
            GiQuality[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum Quality {
        LOW(1),
        BALANCED(2),
        HIGH(4);

        private final int samples;

        Quality(int samples) {
            this.samples = samples;
        }

        public int samples() {
            return samples;
        }

        public Quality next() {
            Quality[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum InternalResolution {
        // Percentage still controls small/windowed viewports. The pixel budget prevents fullscreen
        // from multiplying RT work by 4-6x just because the desktop framebuffer is larger.
        LOW(0.50f, 50, 512 * 288),
        BALANCED(0.67f, 67, 768 * 432),
        HIGH(1.00f, 100, 1280 * 720);

        private final float scale;
        private final int percent;
        private final int maxPixels;

        InternalResolution(float scale, int percent, int maxPixels) {
            this.scale = scale;
            this.percent = percent;
            this.maxPixels = maxPixels;
        }

        public int targetWidth(int viewportWidth, int viewportHeight) {
            int safeViewportWidth = Math.max(1, viewportWidth);
            int safeViewportHeight = Math.max(1, viewportHeight);

            int scaledWidth = Math.max(1, Math.round(safeViewportWidth * scale));
            int scaledHeight = Math.max(1, Math.round(safeViewportHeight * scale));
            long scaledPixels = (long) scaledWidth * scaledHeight;
            if (scaledPixels <= maxPixels) {
                return scaledWidth;
            }

            double reduction = Math.sqrt(maxPixels / (double) scaledPixels);
            return Math.max(1, (int) Math.floor(scaledWidth * reduction));
        }

        public int maxPixels() {
            return maxPixels;
        }

        public int percent() {
            return percent;
        }

        public InternalResolution next() {
            InternalResolution[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum TemporalQuality {
        OFF(0, 0.0f),
        FAST(16, 0.65f),
        STABLE(64, 0.80f);

        private final int historySamples;
        private final float directHistoryWeight;

        TemporalQuality(int historySamples, float directHistoryWeight) {
            this.historySamples = historySamples;
            this.directHistoryWeight = directHistoryWeight;
        }

        public int historySamples() {
            return historySamples;
        }

        public float directHistoryWeight() {
            return directHistoryWeight;
        }

        public TemporalQuality next() {
            TemporalQuality[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum DenoiseQuality {
        OFF(0),
        FAST(1),
        QUALITY(2);

        private final int radius;

        DenoiseQuality(int radius) {
            this.radius = radius;
        }

        public int radius() {
            return radius;
        }

        public DenoiseQuality next() {
            DenoiseQuality[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum LocalLightQuality {
        OFF,
        LOW,
        FULL;

        public LocalLightQuality next() {
            LocalLightQuality[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("totem-lumen.properties");
    private static final AtomicLong REVISION = new AtomicLong();

    private static RenderProfile renderProfile = RenderProfile.TOTEM_LUMEN;
    private static GiQuality giQuality = GiQuality.BALANCED;
    private static Quality shadowQuality = Quality.BALANCED;
    private static int rayDistance = 64;
    private static InternalResolution internalResolution = InternalResolution.BALANCED;
    private static boolean reflectionsEnabled = true;
    private static boolean waterReflections = true;
    private static boolean entityRayTracingEnabled = true;
    private static LocalLightQuality localLightQuality = LocalLightQuality.FULL;
    private static int reflectionBounces = 1;
    private static int reflectionDistance = 64;
    private static TemporalQuality temporalQuality = TemporalQuality.STABLE;
    private static DenoiseQuality denoiseQuality = DenoiseQuality.FAST;

    private RendererSettings() {
    }

    public static synchronized void initialize() {
        if (!Files.isRegularFile(CONFIG_PATH)) return;

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(CONFIG_PATH)) {
            properties.load(input);
            String savedProfile = properties.getProperty("renderProfile");
            if (savedProfile != null) {
                renderProfile = parseRenderProfile(savedProfile, renderProfile);
            } else {
                // Alpha 57 and earlier stored only rendererEnabled. Preserve the user's choice.
                renderProfile = parseBoolean(properties, "rendererEnabled", true)
                        ? RenderProfile.TOTEM_LUMEN
                        : RenderProfile.MINECRAFT_PURE;
            }
            giQuality = parseEnum(properties, "giQuality", GiQuality.class, giQuality);
            shadowQuality = parseEnum(properties, "shadowQuality", Quality.class, shadowQuality);
            rayDistance = sanitizeRayDistance(parseInt(properties, "rayDistance", rayDistance));
            internalResolution = parseEnum(
                    properties,
                    "internalResolution",
                    InternalResolution.class,
                    internalResolution
            );

            int legacyBounces = sanitizeBounces(parseInt(properties, "reflectionBounces", reflectionBounces));
            boolean legacyEnabled = legacyBounces > 0;
            reflectionsEnabled = parseBoolean(properties, "reflectionsEnabled", legacyEnabled);
            waterReflections = parseBoolean(properties, "waterReflections", waterReflections);
            entityRayTracingEnabled = parseBoolean(
                    properties,
                    "entityRayTracingEnabled",
                    entityRayTracingEnabled
            );
            localLightQuality = parseEnum(
                    properties,
                    "localLightQuality",
                    LocalLightQuality.class,
                    localLightQuality
            );
            reflectionBounces = Math.max(1, legacyBounces);
            reflectionDistance = sanitizeReflectionDistance(
                    parseInt(properties, "reflectionDistance", reflectionDistance)
            );
            temporalQuality = parseEnum(
                    properties,
                    "temporalQuality",
                    TemporalQuality.class,
                    temporalQuality
            );
            denoiseQuality = parseEnum(
                    properties,
                    "denoiseQuality",
                    DenoiseQuality.class,
                    denoiseQuality
            );
            TotemLumenClient.LOGGER.info(
                    "Loaded renderer settings: renderProfile={}, gi={}, shadows={}, rayDistance={}, resolution={}, reflections={}, waterReflections={}, entityRayTracing={}, localLights={}, reflectionBounces={}, reflectionDistance={}, temporal={}, denoise={}",
                    renderProfile,
                    giQuality,
                    shadowQuality,
                    rayDistance,
                    internalResolution,
                    reflectionsEnabled,
                    waterReflections,
                    entityRayTracingEnabled,
                    localLightQuality,
                    reflectionBounces,
                    reflectionDistance,
                    temporalQuality,
                    denoiseQuality
            );
        } catch (IOException failure) {
            TotemLumenClient.LOGGER.warn("Failed to read Totem Lumen renderer settings; using defaults", failure);
        }
    }

    public static long revision() {
        return REVISION.get();
    }

    public static synchronized RenderProfile renderProfile() {
        return renderProfile;
    }

    public static synchronized boolean rendererEnabled() {
        // MINECRAFT_RGB deliberately stays on Minecraft's normal world renderer. Its RGB light
        // field is presented by the independent vanilla overlay path, so it must never start or
        // wait for Totem Lumen's monolithic Vulkan pipeline. Only the dedicated Totem profile
        // owns the Vulkan renderer lifecycle.
        return renderProfile == RenderProfile.TOTEM_LUMEN;
    }

    /** Legacy compatibility for callers that still think in terms of an enable toggle. */
    public static synchronized boolean toggleRendererEnabled() {
        renderProfile = rendererEnabled()
                ? RenderProfile.MINECRAFT_PURE
                : RenderProfile.TOTEM_LUMEN;
        changed();
        return rendererEnabled();
    }

    public static synchronized RenderProfile cycleRenderProfile() {
        renderProfile = renderProfile.next();
        changed();
        return renderProfile;
    }

    public static synchronized RenderProfile setRenderProfile(int index) {
        RenderProfile[] values = RenderProfile.values();
        renderProfile = values[Math.max(0, Math.min(values.length - 1, index))];
        changed();
        return renderProfile;
    }

    public static synchronized GiQuality giQuality() {
        return giQuality;
    }

    public static synchronized Quality shadowQuality() {
        return shadowQuality;
    }

    public static synchronized int rayDistance() {
        return rayDistance;
    }

    public static synchronized InternalResolution internalResolution() {
        return internalResolution;
    }

    public static synchronized boolean reflectionsEnabled() {
        return reflectionsEnabled;
    }

    public static synchronized boolean waterReflections() {
        return waterReflections;
    }

    public static synchronized boolean entityRayTracingEnabled() {
        return entityRayTracingEnabled;
    }

    public static synchronized LocalLightQuality localLightQuality() {
        return localLightQuality;
    }

    public static synchronized int reflectionBounces() {
        return reflectionBounces;
    }

    public static synchronized int reflectionDistance() {
        return reflectionDistance;
    }

    public static synchronized TemporalQuality temporalQuality() {
        return temporalQuality;
    }

    public static synchronized DenoiseQuality denoiseQuality() {
        return denoiseQuality;
    }

    public static synchronized GiQuality cycleGiQuality() {
        giQuality = giQuality.next();
        changed();
        return giQuality;
    }

    public static synchronized GiQuality setGiQuality(int index) {
        giQuality = GiQuality.values()[Math.max(0, Math.min(GiQuality.values().length - 1, index))];
        changed();
        return giQuality;
    }

    public static synchronized Quality cycleShadowQuality() {
        shadowQuality = shadowQuality.next();
        changed();
        return shadowQuality;
    }

    public static synchronized Quality setShadowQuality(int index) {
        shadowQuality = Quality.values()[Math.max(0, Math.min(Quality.values().length - 1, index))];
        changed();
        return shadowQuality;
    }

    public static synchronized int cycleRayDistance() {
        rayDistance = switch (rayDistance) {
            case 32 -> 64;
            case 64 -> 96;
            case 96 -> 128;
            default -> 32;
        };
        changed();
        return rayDistance;
    }

    public static synchronized int setRayDistanceIndex(int index) {
        rayDistance = new int[]{32, 64, 96, 128}[Math.max(0, Math.min(3, index))];
        changed();
        return rayDistance;
    }

    public static synchronized InternalResolution cycleInternalResolution() {
        internalResolution = internalResolution.next();
        changed();
        return internalResolution;
    }

    public static synchronized InternalResolution setInternalResolution(int index) {
        internalResolution = InternalResolution.values()[Math.max(0, Math.min(InternalResolution.values().length - 1, index))];
        changed();
        return internalResolution;
    }

    public static synchronized boolean toggleReflectionsEnabled() {
        reflectionsEnabled = !reflectionsEnabled;
        changed();
        return reflectionsEnabled;
    }

    public static synchronized boolean toggleWaterReflections() {
        waterReflections = !waterReflections;
        changed();
        return waterReflections;
    }

    public static synchronized boolean toggleEntityRayTracing() {
        entityRayTracingEnabled = !entityRayTracingEnabled;
        changed();
        return entityRayTracingEnabled;
    }

    public static synchronized LocalLightQuality cycleLocalLightQuality() {
        localLightQuality = localLightQuality.next();
        changed();
        return localLightQuality;
    }

    public static synchronized int cycleReflectionBounces() {
        reflectionBounces = reflectionBounces == 1 ? 2 : 1;
        changed();
        return reflectionBounces;
    }

    public static synchronized int cycleReflectionDistance() {
        reflectionDistance = reflectionDistance == 16 ? 32 : (reflectionDistance == 32 ? 64 : 16);
        changed();
        return reflectionDistance;
    }

    public static synchronized int setReflectionDistanceIndex(int index) {
        return setReflectionDistance(new int[]{16, 32, 64}[Math.max(0, Math.min(2, index))]);
    }

    public static synchronized TemporalQuality cycleTemporalQuality() {
        temporalQuality = temporalQuality.next();
        changed();
        return temporalQuality;
    }

    public static synchronized TemporalQuality setTemporalQuality(int index) {
        temporalQuality = TemporalQuality.values()[Math.max(0, Math.min(TemporalQuality.values().length - 1, index))];
        changed();
        return temporalQuality;
    }

    public static synchronized DenoiseQuality cycleDenoiseQuality() {
        denoiseQuality = denoiseQuality.next();
        changed();
        return denoiseQuality;
    }

    public static synchronized DenoiseQuality setDenoiseQuality(int index) {
        denoiseQuality = DenoiseQuality.values()[Math.max(0, Math.min(DenoiseQuality.values().length - 1, index))];
        changed();
        return denoiseQuality;
    }

    public static synchronized int setReflectionBounces(int value) {
        int sanitized = sanitizeBounces(value);
        if (sanitized == 0) {
            reflectionsEnabled = false;
            reflectionBounces = 1;
        } else {
            reflectionsEnabled = true;
            reflectionBounces = sanitized;
        }
        changed();
        return sanitized;
    }

    public static synchronized int setReflectionDistance(int value) {
        reflectionDistance = sanitizeReflectionDistance(value);
        changed();
        return reflectionDistance;
    }

    private static void changed() {
        REVISION.incrementAndGet();
        save();
    }

    private static int sanitizeBounces(int value) {
        return Math.max(0, Math.min(2, value));
    }

    private static int sanitizeReflectionDistance(int value) {
        if (value <= 16) return 16;
        if (value <= 32) return 32;
        return 64;
    }

    private static int sanitizeRayDistance(int value) {
        if (value <= 32) return 32;
        if (value <= 64) return 64;
        if (value <= 96) return 96;
        return 128;
    }

    private static int parseInt(Properties properties, String key, int fallback) {
        try {
            return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean parseBoolean(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static <E extends Enum<E>> E parseEnum(
            Properties properties,
            String key,
            Class<E> type,
            E fallback
    ) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static RenderProfile parseRenderProfile(String value, RenderProfile fallback) {
        if ("MINECRAFT".equals(value)) {
            // Alpha 59's Minecraft profile already meant vanilla plus RGB lighting.
            return RenderProfile.MINECRAFT_RGB;
        }
        try {
            return RenderProfile.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static void save() {
        Properties properties = new Properties();
        properties.setProperty("renderProfile", renderProfile.name());
        properties.setProperty("rendererEnabled", Boolean.toString(rendererEnabled()));
        properties.setProperty("giQuality", giQuality.name());
        properties.setProperty("shadowQuality", shadowQuality.name());
        properties.setProperty("rayDistance", Integer.toString(rayDistance));
        properties.setProperty("internalResolution", internalResolution.name());
        properties.setProperty("reflectionsEnabled", Boolean.toString(reflectionsEnabled));
        properties.setProperty("waterReflections", Boolean.toString(waterReflections));
        properties.setProperty("entityRayTracingEnabled", Boolean.toString(entityRayTracingEnabled));
        properties.setProperty("localLightQuality", localLightQuality.name());
        properties.setProperty("reflectionBounces", Integer.toString(reflectionBounces));
        properties.setProperty("reflectionDistance", Integer.toString(reflectionDistance));
        properties.setProperty("temporalQuality", temporalQuality.name());
        properties.setProperty("denoiseQuality", denoiseQuality.name());

        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (OutputStream output = Files.newOutputStream(CONFIG_PATH)) {
                properties.store(output, "Totem Lumen renderer settings");
            }
        } catch (IOException failure) {
            TotemLumenClient.LOGGER.warn("Failed to save Totem Lumen renderer settings", failure);
        }
    }
}
