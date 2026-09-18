package dev.totem.lumen.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.material.PbrAnimation;
import dev.totem.lumen.material.PbrImage;
import dev.totem.lumen.material.PbrTextureData;
import dev.totem.lumen.material.PbrTextureHandleRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    private static volatile int loadedAnimated;
    private static volatile boolean firstLoadLogged;

    private LabPbrTextureRegistry() {
    }

    public static void observeSprite(String spriteId) {
        if (spriteId == null || spriteId.isBlank()) return;
        int handle = PbrTextureHandleRegistry.handleFor(spriteId);
        if (handle < 0) {
            TotemLumenClient.LOGGER.warn(
                    "P18 texture-handle capacity exceeded; ignoring sprite {}",
                    spriteId
            );
            return;
        }
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
                if (loaded.animated()) loadedAnimated++;
                if (!firstLoadLogged && (loaded.hasNormalMap() || loaded.hasSpecularMap() || loaded.animated())) {
                    firstLoadLogged = true;
                    TotemLumenClient.LOGGER.info(
                            "P18 texture capture active: sprite={}, normal={}, specular={}, animated={}, format={}",
                            spriteId,
                            loaded.hasNormalMap(),
                            loaded.hasSpecularMap(),
                            loaded.animated(),
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
        loadedAnimated = 0;
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

    public static int loadedAnimatedCount() {
        return loadedAnimated;
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

        LoadedLayer albedo = loadLayer(resources, textureResource(sprite, ""));
        if (albedo == null) {
            return null;
        }
        LoadedLayer normal = loadLayer(resources, textureResource(sprite, "_n"));
        LoadedLayer specular = loadLayer(resources, textureResource(sprite, "_s"));

        PbrAnimation normalAnimation = normal == null ? null : normal.animation();
        PbrAnimation specularAnimation = specular == null ? null : specular.animation();
        if (albedo.animation() != null) {
            if (normal != null && normalAnimation == null) {
                normalAnimation = inheritedAnimation(
                        albedo.image(),
                        albedo.animation(),
                        normal.image()
                );
            }
            if (specular != null && specularAnimation == null) {
                specularAnimation = inheritedAnimation(
                        albedo.image(),
                        albedo.animation(),
                        specular.image()
                );
            }
        }

        return new PbrTextureData(
                spriteId,
                albedo.image(),
                normal == null ? null : normal.image(),
                specular == null ? null : specular.image(),
                albedo.animation(),
                normalAnimation,
                specularAnimation
        );
    }

    private static LoadedLayer loadLayer(ResourceManager resources, Identifier location) {
        try {
            var resource = resources.getResource(location);
            if (resource.isEmpty()) return null;
            PbrImage image;
            try (InputStream input = resource.get().open();
                 NativeImage nativeImage = NativeImage.read(input)) {
                image = new PbrImage(
                        nativeImage.getWidth(),
                        nativeImage.getHeight(),
                        nativeImage.getPixels()
                );
            }

            PbrAnimation animation = loadAnimation(resources, location, image);
            return new LoadedLayer(image, animation);
        } catch (Throwable failure) {
            TotemLumenClient.LOGGER.warn(
                    "P18 failed to decode resource-pack texture {}; ignoring this map",
                    location,
                    failure
            );
            return null;
        }
    }

    private static PbrAnimation loadAnimation(
            ResourceManager resources,
            Identifier textureLocation,
            PbrImage image
    ) {
        Identifier metadataLocation = Identifier.fromNamespaceAndPath(
                textureLocation.getNamespace(),
                textureLocation.getPath() + ".mcmeta"
        );
        try {
            var metadataResource = resources.getResource(metadataLocation);
            if (metadataResource.isEmpty()) return null;

            JsonObject root;
            try (InputStream input = metadataResource.get().open();
                 InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) return null;
                root = parsed.getAsJsonObject();
            }

            JsonObject animationObject = root.has("animation") && root.get("animation").isJsonObject()
                    ? root.getAsJsonObject("animation")
                    : null;
            if (animationObject == null) return null;

            int frameWidth = positiveInt(animationObject, "width", image.width());
            // Minecraft defaults both missing dimensions to the texture width, which makes the
            // common 16x(N*16) vertical strip animate as 16x16 frames.
            int frameHeight = positiveInt(animationObject, "height", image.width());
            if (image.width() % frameWidth != 0 || image.height() % frameHeight != 0) {
                TotemLumenClient.LOGGER.warn(
                        "P18 animation metadata dimensions do not tile texture {}; image={}x{}, frame={}x{}",
                        textureLocation,
                        image.width(),
                        image.height(),
                        frameWidth,
                        frameHeight
                );
                return null;
            }

            int columns = image.width() / frameWidth;
            int rows = image.height() / frameHeight;
            int sourceFrameCount = Math.multiplyExact(columns, rows);
            int defaultFrameTime = positiveInt(animationObject, "frametime", 1);
            boolean interpolate = animationObject.has("interpolate")
                    && animationObject.get("interpolate").isJsonPrimitive()
                    && animationObject.get("interpolate").getAsBoolean();

            List<Integer> timeline = new ArrayList<>();
            JsonArray frames = animationObject.has("frames") && animationObject.get("frames").isJsonArray()
                    ? animationObject.getAsJsonArray("frames")
                    : null;
            if (frames == null || frames.size() == 0) {
                for (int frame = 0; frame < sourceFrameCount; frame++) {
                    appendFrameTicks(timeline, frame, defaultFrameTime);
                }
            } else {
                for (JsonElement entry : frames) {
                    int frameIndex;
                    int frameTime = defaultFrameTime;
                    if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isNumber()) {
                        frameIndex = entry.getAsInt();
                    } else if (entry.isJsonObject()) {
                        JsonObject frameObject = entry.getAsJsonObject();
                        if (!frameObject.has("index")) continue;
                        frameIndex = frameObject.get("index").getAsInt();
                        frameTime = positiveInt(frameObject, "time", defaultFrameTime);
                    } else {
                        continue;
                    }

                    if (frameIndex < 0 || frameIndex >= sourceFrameCount) {
                        TotemLumenClient.LOGGER.warn(
                                "P18 animation frame {} is outside {} source frames for {}",
                                frameIndex,
                                sourceFrameCount,
                                textureLocation
                        );
                        continue;
                    }
                    appendFrameTicks(timeline, frameIndex, frameTime);
                }
            }

            if (timeline.isEmpty()) return null;
            int[] expanded = timeline.stream().mapToInt(Integer::intValue).toArray();
            TotemLumenClient.LOGGER.info(
                    "P18 animated texture discovered: texture={}, frame={}x{}, sourceFrames={}, timelineTicks={}, interpolate={}",
                    textureLocation,
                    frameWidth,
                    frameHeight,
                    sourceFrameCount,
                    expanded.length,
                    interpolate
            );
            return new PbrAnimation(frameWidth, frameHeight, expanded, interpolate);
        } catch (Throwable failure) {
            TotemLumenClient.LOGGER.warn(
                    "P18 failed to read animation metadata {}; treating texture as static",
                    metadataLocation,
                    failure
            );
            return null;
        }
    }

    private static PbrAnimation inheritedAnimation(
            PbrImage sourceImage,
            PbrAnimation sourceAnimation,
            PbrImage targetImage
    ) {
        int columns = sourceImage.width() / sourceAnimation.frameWidth();
        int rows = sourceImage.height() / sourceAnimation.frameHeight();
        if (columns <= 0 || rows <= 0
                || targetImage.width() % columns != 0
                || targetImage.height() % rows != 0) {
            return null;
        }
        int frameWidth = targetImage.width() / columns;
        int frameHeight = targetImage.height() / rows;
        return new PbrAnimation(
                frameWidth,
                frameHeight,
                sourceAnimation.copyTimelineFrames(),
                sourceAnimation.interpolate()
        );
    }

    private static int positiveInt(JsonObject object, String key, int fallback) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) return fallback;
        try {
            int value = object.get(key).getAsInt();
            return value > 0 ? value : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static void appendFrameTicks(List<Integer> timeline, int frameIndex, int ticks) {
        int boundedTicks = Math.min(Math.max(ticks, 1), 4096);
        for (int tick = 0; tick < boundedTicks; tick++) {
            timeline.add(frameIndex);
        }
    }

    private static Identifier textureResource(Identifier sprite, String suffix) {
        String path = "textures/" + sprite.getPath() + suffix + ".png";
        return Identifier.fromNamespaceAndPath(sprite.getNamespace(), path);
    }

    private record LoadedLayer(PbrImage image, PbrAnimation animation) {
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
