package dev.totem.lumen.integration;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.resources.metadata.animation.AnimationFrame;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.material.PbrAnimation;
import dev.totem.lumen.material.PbrImage;
import dev.totem.lumen.material.PbrTextureData;
import dev.totem.lumen.material.PbrTextureHandleRegistry;
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
        PbrTextureMaterialRuleRegistry.ensureLoaded(resources);
        BlockMaterialRuleRegistry.ensureLoaded(resources);
        RendererRuntimeTuningRegistry.ensureLoaded(resources);
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
        PbrTextureMaterialRuleRegistry.invalidate();
        BlockMaterialRuleRegistry.invalidate();
        RendererRuntimeTuningRegistry.invalidate();
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

    public static long animationSignature(long gameTick) {
        long hash = 0xCBF29CE484222325L;
        List<PbrTextureData> textures = snapshot();
        for (PbrTextureData texture : textures) {
            if (!texture.animated()) continue;
            hash ^= texture.spriteId().hashCode();
            hash *= 0x100000001B3L;
            if (texture.albedoAnimation() != null) {
                hash ^= texture.albedoAnimation().frameAtTick(gameTick);
                hash *= 0x100000001B3L;
            }
            if (texture.normalAnimation() != null) {
                hash ^= texture.normalAnimation().frameAtTick(gameTick);
                hash *= 0x100000001B3L;
            }
            if (texture.specularAnimation() != null) {
                hash ^= texture.specularAnimation().frameAtTick(gameTick);
                hash *= 0x100000001B3L;
            }
        }
        return hash;
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
                specularAnimation,
                PbrTextureMaterialRuleRegistry.propertiesFor(spriteId)
        );
    }

    private static LoadedLayer loadLayer(ResourceManager resources, Identifier location) {
        try {
            var resourceOptional = resources.getResource(location);
            if (resourceOptional.isEmpty()) return null;
            var resource = resourceOptional.get();

            PbrImage image;
            try (InputStream input = resource.open();
                 NativeImage nativeImage = NativeImage.read(input)) {
                image = new PbrImage(
                        nativeImage.getWidth(),
                        nativeImage.getHeight(),
                        nativeImage.getPixels()
                );
            }

            PbrAnimation animation = loadAnimation(resource, location, image);
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
            net.minecraft.server.packs.resources.Resource resource,
            Identifier textureLocation,
            PbrImage image
    ) {
        try {
            var metadata = resource.metadata().getSection(AnimationMetadataSection.TYPE);
            if (metadata.isEmpty()) return null;

            AnimationMetadataSection animation = metadata.get();
            FrameSize frameSize = animation.calculateFrameSize(image.width(), image.height());
            int frameWidth = frameSize.width();
            int frameHeight = frameSize.height();
            if (frameWidth <= 0 || frameHeight <= 0
                    || image.width() % frameWidth != 0
                    || image.height() % frameHeight != 0) {
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
            int defaultFrameTime = Math.max(1, animation.defaultFrameTime());

            List<Integer> timeline = new ArrayList<>();
            List<AnimationFrame> frames = animation.frames().orElse(null);
            if (frames == null || frames.isEmpty()) {
                for (int frame = 0; frame < sourceFrameCount; frame++) {
                    appendFrameTicks(timeline, frame, defaultFrameTime);
                }
            } else {
                for (AnimationFrame entry : frames) {
                    int frameIndex = entry.index();
                    if (frameIndex < 0 || frameIndex >= sourceFrameCount) {
                        TotemLumenClient.LOGGER.warn(
                                "P18 animation frame {} is outside {} source frames for {}",
                                frameIndex,
                                sourceFrameCount,
                                textureLocation
                        );
                        continue;
                    }
                    appendFrameTicks(
                            timeline,
                            frameIndex,
                            Math.max(1, entry.timeOr(defaultFrameTime))
                    );
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
                    animation.interpolatedFrames()
            );
            return new PbrAnimation(
                    frameWidth,
                    frameHeight,
                    expanded,
                    animation.interpolatedFrames()
            );
        } catch (Throwable failure) {
            TotemLumenClient.LOGGER.warn(
                    "P18 failed to read animation metadata for {}; treating texture as static",
                    textureLocation,
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
