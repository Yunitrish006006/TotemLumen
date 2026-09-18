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
        LOW(120),
        BALANCED(160),
        HIGH(240);

        private final int width;

        InternalResolution(int width) {
            this.width = width;
        }

        public int width() {
            return width;
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

    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("totem-lumen.properties");
    private static final AtomicLong REVISION = new AtomicLong();

    private static boolean rendererEnabled = true;
    private static Quality giQuality = Quality.BALANCED;
    private static Quality shadowQuality = Quality.BALANCED;
    private static int rayDistance = 256;
    private static InternalResolution internalResolution = InternalResolution.BALANCED;
    private static boolean reflectionsEnabled = true;
    private static boolean waterReflections = true;
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
            rendererEnabled = parseBoolean(properties, "rendererEnabled", rendererEnabled);
            giQuality = parseEnum(properties, "giQuality", Quality.class, giQuality);
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
                    "Loaded renderer settings: rendererEnabled={}, gi={}, shadows={}, rayDistance={}, resolution={}, reflections={}, waterReflections={}, reflectionBounces={}, reflectionDistance={}, temporal={}, denoise={}",
                    rendererEnabled,
                    giQuality,
                    shadowQuality,
                    rayDistance,
                    internalResolution,
                    reflectionsEnabled,
                    waterReflections,
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

    public static synchronized boolean rendererEnabled() {
        return rendererEnabled;
    }

    public static synchronized boolean toggleRendererEnabled() {
        rendererEnabled = !rendererEnabled;
        changed();
        return rendererEnabled;
    }

    public static synchronized Quality giQuality() {
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

    public static synchronized Quality cycleGiQuality() {
        giQuality = giQuality.next();
        changed();
        return giQuality;
    }

    public static synchronized Quality cycleShadowQuality() {
        shadowQuality = shadowQuality.next();
        changed();
        return shadowQuality;
    }

    public static synchronized int cycleRayDistance() {
        rayDistance = rayDistance == 64 ? 128 : (rayDistance == 128 ? 256 : 64);
        changed();
        return rayDistance;
    }

    public static synchronized InternalResolution cycleInternalResolution() {
        internalResolution = internalResolution.next();
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

    public static synchronized TemporalQuality cycleTemporalQuality() {
        temporalQuality = temporalQuality.next();
        changed();
        return temporalQuality;
    }

    public static synchronized DenoiseQuality cycleDenoiseQuality() {
        denoiseQuality = denoiseQuality.next();
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
        if (value <= 64) return 64;
        if (value <= 128) return 128;
        return 256;
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

    private static void save() {
        Properties properties = new Properties();
        properties.setProperty("rendererEnabled", Boolean.toString(rendererEnabled));
        properties.setProperty("giQuality", giQuality.name());
        properties.setProperty("shadowQuality", shadowQuality.name());
        properties.setProperty("rayDistance", Integer.toString(rayDistance));
        properties.setProperty("internalResolution", internalResolution.name());
        properties.setProperty("reflectionsEnabled", Boolean.toString(reflectionsEnabled));
        properties.setProperty("waterReflections", Boolean.toString(waterReflections));
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
