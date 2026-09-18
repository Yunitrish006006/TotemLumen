package dev.totem.lumen.integration;

import com.mojang.blaze3d.platform.NativeImage;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.material.PbrImage;
import dev.totem.lumen.material.PbrTextureData;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * P18 resource-pack texture cache.
 *
 * <p>Chunk/model extraction threads only call {@link #observeSprite(String)}. PNG decoding happens
 * incrementally on the client tick so high-resolution resource packs cannot stall section meshing.
 * Loaded pixel arrays are Totem-owned and contain no live Minecraft texture/image objects.</p>
 */
public final class LabPbrTextureRegistry {
    public enum DeclaredFormat {
        UNDECLARED,
        LABPBR_1_3,
        LABPBR_OTHER
    }

    private static final int LOADS_PER_TICK = 4;
    private static final Identifier TEXTURE_PROPERTIES =
            Identifier.fromNamespaceAndPath("minecraft", "optifine/texture.properties");

    private static final Set<String> KNOWN_SPRITES = ConcurrentHashMap.newKeySet();
    private static final Set<String> QUEUED_SPRITES = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<String> LOAD_QUEUE = new ConcurrentLinkedQueue<>();
    private static final Map<String, PbrTextureData> TEXTURES = new ConcurrentHashMap<>();

    private static volatile long revision;
    private static volatile long reloadGeneration;
    private static volatile boolean formatScanned;
    private static volatile DeclaredFormat declaredFormat = DeclaredFormat.UNDECLARED;
    private static volatile int loadedWithNormal;
    private static volatile int loadedWithSpecular;
    private static volatile boolean firstLoadLogged;

    private LabPbrTextureRegistry() {
    }

    public static void observeSprite(String spriteId) {
        if (spriteId == null || spriteId.isBlank()) return;
        KNOWN_SPRITES.add(spriteId);
        if (!TEXTURES.containsKey(spriteId) && QUEUED_SPRITES.add(spriteId)) {
            LOAD_QUEUE.add(spriteId);
        }
    }

    public static void tick(Minecraft client) {
        if (client == null) return;
        ResourceManager resources = client.getResourceManager();
        if (!formatScanned) {
            scanDeclaredFormat(resources);
        }

        int loadedThisTick = 0;
        while (loadedThisTick < LOADS_PER_TICK) {
            String spriteId = LOAD_QUEUE.poll();
            if (spriteId == null) break;
            QUEUED_SPRITES.remove(spriteId);

            PbrTextureData loaded = load(resources, spriteId);
            if (loaded != null) {
                PbrTextureData previous = TEXTURES.put(spriteId, loaded);
                if (previous == null || !previous.equals(loaded)) {
                    revision++;
                }
                if (loaded.hasNormalMap()) loadedWithNormal++;
                if (loaded.hasSpecularMap()) loadedWithSpecular++;
                if (!firstLoadLogged && (loaded.hasNormalMap() || loaded.hasSpecularMap())) {
                    firstLoadLogged = true;
                    TotemLumenClient.LOGGER.info(
                            "P18 LabPBR texture capture active: sprite={}, normal={}, specular={}, format={}",
                            spriteId,
                            loaded.hasNormalMap(),
                            loaded.hasSpecularMap(),
                            declaredFormat
                    );
                }
            }
            loadedThisTick++;
        }
    }

    public static synchronized void onResourceReload() {
        TEXTURES.clear();
        LOAD_QUEUE.clear();
        QUEUED_SPRITES.clear();
        for (String spriteId : KNOWN_SPRITES) {
            if (QUEUED_SPRITES.add(spriteId)) {
                LOAD_QUEUE.add(spriteId);
            }
        }
        loadedWithNormal = 0;
        loadedWithSpecular = 0;
        firstLoadLogged = false;
        formatScanned = false;
        declaredFormat = DeclaredFormat.UNDECLARED;
        reloadGeneration++;
        revision++;
        TotemLumenClient.LOGGER.info(
                "P18 resource-pack reload: generation={}, knownSprites={}, queued={}",
                reloadGeneration,
                KNOWN_SPRITES.size(),
                LOAD_QUEUE.size()
        );
    }

    public static PbrTextureData texture(String spriteId) {
        return TEXTURES.get(spriteId);
    }

    public static List<PbrTextureData> snapshot() {
        List<PbrTextureData> result = new ArrayList<>(TEXTURES.values());
        result.sort(Comparator.comparing(PbrTextureData::spriteId));
        return List.copyOf(result);
    }

    public static long revision() {
        return revision;
    }

    public static long reloadGeneration() {
        return reloadGeneration;
    }

    public static DeclaredFormat declaredFormat() {
        return declaredFormat;
    }

    public static int loadedTextureCount() {
        return TEXTURES.size();
    }

    public static int loadedWithNormalCount() {
        return loadedWithNormal;
    }

    public static int loadedWithSpecularCount() {
        return loadedWithSpecular;
    }

    private static void scanDeclaredFormat(ResourceManager resources) {
        DeclaredFormat format = DeclaredFormat.UNDECLARED;
        try {
            var resource = resources.getResource(TEXTURE_PROPERTIES);
            if (resource.isPresent()) {
                Properties properties = new Properties();
                try (InputStream input = resource.get().open()) {
                    properties.load(input);
                }
                String value = properties.getProperty("format", "").trim().toLowerCase();
                if (value.equals("lab-pbr/1.3") || value.equals("labpbr/1.3")) {
                    format = DeclaredFormat.LABPBR_1_3;
                } else if (value.startsWith("lab-pbr") || value.startsWith("labpbr")) {
                    format = DeclaredFormat.LABPBR_OTHER;
                }
            }
        } catch (Throwable failure) {
            TotemLumenClient.LOGGER.warn(
                    "P18 could not read optifine/texture.properties; auxiliary maps will still be discovered lazily",
                    failure
            );
        }
        declaredFormat = format;
        formatScanned = true;
        TotemLumenClient.LOGGER.info("P18 resource-pack material declaration: {}", format);
    }

    private static PbrTextureData load(ResourceManager resources, String spriteId) {
        Identifier sprite = parseSpriteId(spriteId);
        if (sprite == null) return null;

        PbrImage albedo = loadImage(resources, textureResource(sprite, ""));
        if (albedo == null) {
            return null;
        }
        PbrImage normal = loadImage(resources, textureResource(sprite, "_n"));
        PbrImage specular = loadImage(resources, textureResource(sprite, "_s"));
        return new PbrTextureData(spriteId, albedo, normal, specular);
    }

    private static PbrImage loadImage(ResourceManager resources, Identifier location) {
        try {
            var resource = resources.getResource(location);
            if (resource.isEmpty()) return null;
            try (InputStream input = resource.get().open();
                 NativeImage image = NativeImage.read(input)) {
                return new PbrImage(
                        image.getWidth(),
                        image.getHeight(),
                        image.getPixels()
                );
            }
        } catch (Throwable failure) {
            TotemLumenClient.LOGGER.warn(
                    "P18 failed to decode resource-pack texture {}; ignoring this map",
                    location,
                    failure
            );
            return null;
        }
    }

    private static Identifier textureResource(Identifier sprite, String suffix) {
        String path = "textures/" + sprite.getPath() + suffix + ".png";
        return Identifier.fromNamespaceAndPath(sprite.getNamespace(), path);
    }

    private static Identifier parseSpriteId(String spriteId) {
        int colon = spriteId.indexOf(':');
        if (colon <= 0 || colon == spriteId.length() - 1) return null;
        try {
            return Identifier.fromNamespaceAndPath(
                    spriteId.substring(0, colon),
                    spriteId.substring(colon + 1)
            );
        } catch (Throwable ignored) {
            return null;
        }
    }
}
