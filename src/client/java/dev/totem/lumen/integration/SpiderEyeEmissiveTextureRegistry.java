package dev.totem.lumen.integration;

import com.mojang.blaze3d.platform.NativeImage;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.material.PbrImage;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;

/**
 * Resource-pack aware source for the shared spider-eye emissive overlay.
 *
 * <p>Minecraft 26.x packs may place the texture under entity/spider while older packs use the
 * historical entity root path. Both are accepted so the ray-traced emissive mask follows the
 * active resource pack rather than a hard-coded vanilla texel layout.</p>
 */
public final class SpiderEyeEmissiveTextureRegistry {
    private static final Identifier MODERN =
            Identifier.fromNamespaceAndPath("minecraft", "textures/entity/spider/spider_eyes.png");
    private static final Identifier LEGACY =
            Identifier.fromNamespaceAndPath("minecraft", "textures/entity/spider_eyes.png");

    private static volatile PbrImage image;
    private static volatile Identifier resolvedLocation;
    private static volatile boolean loaded;
    private static volatile long revision;

    private SpiderEyeEmissiveTextureRegistry() {
    }

    public static void ensureLoaded(ResourceManager resources) {
        if (loaded) return;
        synchronized (SpiderEyeEmissiveTextureRegistry.class) {
            if (loaded) return;
            Loaded decoded = loadFirst(resources, MODERN, LEGACY);
            image = decoded == null ? null : decoded.image();
            resolvedLocation = decoded == null ? null : decoded.location();
            loaded = true;
            revision++;
            if (image != null) {
                TotemLumenClient.LOGGER.info(
                        "P17 spider-eye emissive texture loaded: location={}, size={}x{}, revision={}",
                        resolvedLocation,
                        image.width(),
                        image.height(),
                        revision
                );
            } else {
                TotemLumenClient.LOGGER.warn(
                        "P17 spider-eye emissive texture was not found; spider eye glow is disabled"
                );
            }
        }
    }

    public static PbrImage image() {
        return image;
    }

    public static Identifier resolvedLocation() {
        return resolvedLocation;
    }

    public static long revision() {
        return revision;
    }

    public static synchronized void invalidate() {
        image = null;
        resolvedLocation = null;
        loaded = false;
        revision++;
    }

    private static Loaded loadFirst(ResourceManager resources, Identifier... locations) {
        for (Identifier location : locations) {
            try {
                var resource = resources.getResource(location);
                if (resource.isEmpty()) continue;
                try (InputStream input = resource.get().open();
                     NativeImage nativeImage = NativeImage.read(input)) {
                    return new Loaded(
                            location,
                            new PbrImage(
                                    nativeImage.getWidth(),
                                    nativeImage.getHeight(),
                                    nativeImage.getPixels()
                            )
                    );
                }
            } catch (Throwable failure) {
                TotemLumenClient.LOGGER.warn(
                        "P17 failed to decode spider-eye emissive texture {}",
                        location,
                        failure
                );
            }
        }
        return null;
    }

    private record Loaded(Identifier location, PbrImage image) {
    }
}
