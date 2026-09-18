package dev.totem.lumen.render;

import dev.totem.lumen.TotemLumenClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Persistent client-side renderer quality settings. */
public final class RendererSettings {
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("totem-lumen.properties");

    private static int reflectionBounces = 1;
    private static int reflectionDistance = 64;

    private RendererSettings() {
    }

    public static synchronized void initialize() {
        if (!Files.isRegularFile(CONFIG_PATH)) return;

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(CONFIG_PATH)) {
            properties.load(input);
            reflectionBounces = sanitizeBounces(parseInt(properties, "reflectionBounces", reflectionBounces));
            reflectionDistance = sanitizeDistance(parseInt(properties, "reflectionDistance", reflectionDistance));
            TotemLumenClient.LOGGER.info(
                    "Loaded renderer settings: reflectionBounces={}, reflectionDistance={}",
                    reflectionBounces,
                    reflectionDistance
            );
        } catch (IOException failure) {
            TotemLumenClient.LOGGER.warn("Failed to read Totem Lumen renderer settings; using defaults", failure);
        }
    }

    public static synchronized int reflectionBounces() {
        return reflectionBounces;
    }

    public static synchronized int reflectionDistance() {
        return reflectionDistance;
    }

    public static synchronized int cycleReflectionBounces() {
        return setReflectionBounces((reflectionBounces + 1) % 3);
    }

    public static synchronized int cycleReflectionDistance() {
        int next = reflectionDistance == 16 ? 32 : (reflectionDistance == 32 ? 64 : 16);
        return setReflectionDistance(next);
    }

    public static synchronized int setReflectionBounces(int value) {
        reflectionBounces = sanitizeBounces(value);
        save();
        return reflectionBounces;
    }

    public static synchronized int setReflectionDistance(int value) {
        reflectionDistance = sanitizeDistance(value);
        save();
        return reflectionDistance;
    }

    private static int sanitizeBounces(int value) {
        return Math.max(0, Math.min(2, value));
    }

    private static int sanitizeDistance(int value) {
        if (value <= 16) return 16;
        if (value <= 32) return 32;
        return 64;
    }

    private static int parseInt(Properties properties, String key, int fallback) {
        try {
            return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void save() {
        Properties properties = new Properties();
        properties.setProperty("reflectionBounces", Integer.toString(reflectionBounces));
        properties.setProperty("reflectionDistance", Integer.toString(reflectionDistance));

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
