package dev.totem.lumen.gpu;

import dev.totem.lumen.material.PbrAnimation;
import dev.totem.lumen.material.PbrImage;
import dev.totem.lumen.material.PbrTextureData;
import dev.totem.lumen.material.PbrTextureHandleRegistry;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fixed-capacity P18 texture tail.
 *
 * <p>Animated textures are packed once: unique frame combinations live in the texel pool and an
 * O(1) per-game-tick timeline selects the current packed frame in the shader. Albedo, normal and
 * specular maps may each use independent mcmeta timelines; repeated frame combinations are
 * deduplicated before upload.</p>
 */
public final class GpuPbrTextureScene {
    public static final int ABI_VERSION = 3;
    public static final int MAX_TEXTURE_HANDLES = PbrTextureHandleRegistry.MAX_HANDLE + 1;
    public static final int MAX_TEXTURE_DIMENSION = 128;
    public static final int MAX_TEXELS = 2_097_152;
    public static final int MAX_ANIMATION_TIMELINE_WORDS = 65_536;
    public static final int MAX_TIMELINE_TICKS_PER_TEXTURE = 4_096;

    public static final int FLAG_HAS_NORMAL = 1;
    public static final int FLAG_HAS_SPECULAR = 1 << 1;
    public static final int FLAG_HAS_ALPHA = 1 << 2;
    public static final int FLAG_DOWNSAMPLED = 1 << 3;
    public static final int FLAG_ANIMATED = 1 << 4;
    public static final int FLAG_INTERPOLATE_REQUESTED = 1 << 5;
    public static final int FLAG_TIMELINE_TRUNCATED = 1 << 6;

    public static final int HEADER_WORDS = 8;
    public static final int DESCRIPTOR_WORDS_PER_RECORD = 16;
    public static final int DESCRIPTOR_WORDS =
            MAX_TEXTURE_HANDLES * DESCRIPTOR_WORDS_PER_RECORD;
    public static final int ANIMATION_POOL_WORDS = MAX_ANIMATION_TIMELINE_WORDS;
    public static final int TEXEL_WORDS_PER_RECORD = 3;
    public static final int TEXEL_POOL_WORDS = MAX_TEXELS * TEXEL_WORDS_PER_RECORD;

    public static final int DESCRIPTOR_BASE_WORD = HEADER_WORDS;
    public static final int ANIMATION_POOL_BASE_WORD = DESCRIPTOR_BASE_WORD + DESCRIPTOR_WORDS;
    public static final int TEXEL_POOL_BASE_WORD = ANIMATION_POOL_BASE_WORD + ANIMATION_POOL_WORDS;
    public static final int MAX_STORAGE_WORDS = TEXEL_POOL_BASE_WORD + TEXEL_POOL_WORDS;
    public static final long MAX_STORAGE_BYTES = (long) MAX_STORAGE_WORDS * Integer.BYTES;

    private GpuPbrTextureScene() {
    }

    public static PackResult pack(
            ByteBuffer buffer,
            int baseWord,
            List<PbrTextureData> textures
    ) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(textures, "textures");
        if (baseWord < 0) throw new IllegalArgumentException("baseWord must be >= 0");

        long requiredCapacity = ((long) baseWord + MAX_STORAGE_WORDS) * Integer.BYTES;
        if (requiredCapacity > buffer.capacity()) {
            throw new IllegalStateException(
                    "P18 texture storage exceeds scene buffer: requiredCapacity="
                            + requiredCapacity + ", capacity=" + buffer.capacity()
            );
        }

        // Clear header + descriptors + animation timeline pool. The texel pool is append-only for
        // this packing pass and stale texels become unreachable after descriptors are zeroed.
        for (int word = 0; word < TEXEL_POOL_BASE_WORD; word++) {
            putWord(buffer, baseWord + word, 0);
        }

        List<PbrTextureData> sorted = new ArrayList<>(textures);
        sorted.sort(
                Comparator.<PbrTextureData>comparingInt(
                        texture -> {
                            int handle = PbrTextureHandleRegistry.existingHandle(texture.spriteId());
                            return handle <= 0 ? Integer.MAX_VALUE : handle;
                        }
                ).thenComparing(PbrTextureData::spriteId)
        );

        int nextTexel = 0;
        int nextTimelineWord = 0;
        int packedTextures = 0;
        int droppedTextures = 0;
        int downsampledTextures = 0;
        int alphaTextures = 0;
        int animatedTextures = 0;
        int interpolatedAnimations = 0;
        int truncatedAnimations = 0;

        for (PbrTextureData texture : sorted) {
            int handle = PbrTextureHandleRegistry.existingHandle(texture.spriteId());
            if (handle <= 0 || handle >= MAX_TEXTURE_HANDLES) {
                droppedTextures++;
                continue;
            }

            int logicalWidth = texture.albedoAnimation() == null
                    ? texture.albedo().width()
                    : texture.albedoAnimation().frameWidth();
            int logicalHeight = texture.albedoAnimation() == null
                    ? texture.albedo().height()
                    : texture.albedoAnimation().frameHeight();
            Dimensions dimensions = boundedDimensions(logicalWidth, logicalHeight);
            int texelsPerFrame = Math.multiplyExact(dimensions.width(), dimensions.height());

            AnimationPlan animation = buildAnimationPlan(texture);
            int packedFrameCount = animation.states().size();
            long requiredTexels = (long) texelsPerFrame * packedFrameCount;
            if ((long) nextTexel + requiredTexels > MAX_TEXELS) {
                droppedTextures++;
                continue;
            }
            if (animation.timeline().length > 0
                    && (long) nextTimelineWord + animation.timeline().length > ANIMATION_POOL_WORDS) {
                droppedTextures++;
                continue;
            }

            boolean hasNormal = texture.hasNormalMap();
            boolean hasSpecular = texture.hasSpecularMap();
            boolean hasAlpha = false;
            boolean downsampled = dimensions.width() != logicalWidth
                    || dimensions.height() != logicalHeight;

            int firstTexel = nextTexel;
            for (FrameState state : animation.states()) {
                int texelBase = baseWord + TEXEL_POOL_BASE_WORD
                        + nextTexel * TEXEL_WORDS_PER_RECORD;
                for (int y = 0; y < dimensions.height(); y++) {
                    float v = (y + 0.5f) / dimensions.height();
                    for (int x = 0; x < dimensions.width(); x++) {
                        float u = (x + 0.5f) / dimensions.width();
                        int texelIndex = y * dimensions.width() + x;
                        int word = texelBase + texelIndex * TEXEL_WORDS_PER_RECORD;

                        int albedo = sampleFrameNearest(
                                texture.albedo(),
                                texture.albedoAnimation(),
                                state.albedoFrame(),
                                u,
                                v
                        );
                        if (((albedo >>> 24) & 0xFF) < 255) hasAlpha = true;
                        putWord(buffer, word, albedo);

                        int normal = hasNormal
                                ? sampleFrameNearest(
                                        texture.normal(),
                                        texture.normalAnimation(),
                                        state.normalFrame(),
                                        u,
                                        v
                                )
                                : 0;
                        int specular = hasSpecular
                                ? sampleFrameNearest(
                                        texture.specular(),
                                        texture.specularAnimation(),
                                        state.specularFrame(),
                                        u,
                                        v
                                )
                                : 0;
                        putWord(buffer, word + 1, normal);
                        putWord(buffer, word + 2, specular);
                    }
                }
                nextTexel += texelsPerFrame;
            }

            int timelineOffset = nextTimelineWord;
            for (int packedFrame : animation.timeline()) {
                putWord(
                        buffer,
                        baseWord + ANIMATION_POOL_BASE_WORD + nextTimelineWord,
                        packedFrame
                );
                nextTimelineWord++;
            }

            int flags = 0;
            if (hasNormal) flags |= FLAG_HAS_NORMAL;
            if (hasSpecular) flags |= FLAG_HAS_SPECULAR;
            if (hasAlpha) flags |= FLAG_HAS_ALPHA;
            if (downsampled) flags |= FLAG_DOWNSAMPLED;
            if (animation.animated()) flags |= FLAG_ANIMATED;
            if (animation.interpolateRequested()) flags |= FLAG_INTERPOLATE_REQUESTED;
            if (animation.truncated()) flags |= FLAG_TIMELINE_TRUNCATED;

            int descriptor = baseWord + DESCRIPTOR_BASE_WORD
                    + handle * DESCRIPTOR_WORDS_PER_RECORD;
            putWord(buffer, descriptor, firstTexel);
            putWord(buffer, descriptor + 1, dimensions.width());
            putWord(buffer, descriptor + 2, dimensions.height());
            putWord(buffer, descriptor + 3, flags);
            putWord(buffer, descriptor + 4, logicalWidth);
            putWord(buffer, descriptor + 5, logicalHeight);
            putWord(buffer, descriptor + 6, texture.spriteId().hashCode());
            putWord(buffer, descriptor + 7, handle);
            putWord(buffer, descriptor + 8, packedFrameCount);
            putWord(buffer, descriptor + 9, timelineOffset);
            putWord(buffer, descriptor + 10, animation.timeline().length);
            putWord(
                    buffer,
                    descriptor + 11,
                    Float.floatToRawIntBits(texture.runtimeProperties().baselineEmissionScale())
            );
            putWord(
                    buffer,
                    descriptor + 12,
                    Float.floatToRawIntBits(texture.runtimeProperties().labPbrEmissionScale())
            );
            putWord(
                    buffer,
                    descriptor + 13,
                    Float.floatToRawIntBits(texture.runtimeProperties().roughnessScale())
            );
            putWord(
                    buffer,
                    descriptor + 14,
                    Float.floatToRawIntBits(texture.runtimeProperties().normalStrength())
            );
            putWord(
                    buffer,
                    descriptor + 15,
                    Float.floatToRawIntBits(texture.runtimeProperties().alphaCutoff())
            );

            packedTextures++;
            if (downsampled) downsampledTextures++;
            if (hasAlpha) alphaTextures++;
            if (animation.animated()) animatedTextures++;
            if (animation.interpolateRequested()) interpolatedAnimations++;
            if (animation.truncated()) truncatedAnimations++;
        }

        putWord(buffer, baseWord, ABI_VERSION);
        putWord(buffer, baseWord + 1, packedTextures);
        putWord(buffer, baseWord + 2, nextTexel);
        putWord(buffer, baseWord + 3, droppedTextures);
        putWord(buffer, baseWord + 4, downsampledTextures);
        putWord(buffer, baseWord + 5, alphaTextures);
        putWord(buffer, baseWord + 6, animatedTextures);
        putWord(buffer, baseWord + 7, nextTimelineWord);

        int usedWords = TEXEL_POOL_BASE_WORD
                + nextTexel * TEXEL_WORDS_PER_RECORD;
        return new PackResult(
                packedTextures,
                nextTexel,
                droppedTextures,
                downsampledTextures,
                alphaTextures,
                animatedTextures,
                interpolatedAnimations,
                truncatedAnimations,
                nextTimelineWord,
                usedWords
        );
    }

    static AnimationPlan buildAnimationPlan(PbrTextureData texture) {
        PbrAnimation albedo = texture.albedoAnimation();
        PbrAnimation normal = texture.normalAnimation();
        PbrAnimation specular = texture.specularAnimation();
        boolean animated = albedo != null || normal != null || specular != null;
        boolean interpolate = (albedo != null && albedo.interpolate())
                || (normal != null && normal.interpolate())
                || (specular != null && specular.interpolate());

        if (!animated) {
            return new AnimationPlan(
                    List.of(new FrameState(0, 0, 0)),
                    new int[0],
                    false,
                    false,
                    false
            );
        }

        int cycle = 1;
        boolean truncated = false;
        for (PbrAnimation layer : new PbrAnimation[]{albedo, normal, specular}) {
            if (layer == null) continue;
            long next = lcm(cycle, layer.timelineLength());
            if (next > MAX_TIMELINE_TICKS_PER_TEXTURE) {
                cycle = MAX_TIMELINE_TICKS_PER_TEXTURE;
                truncated = true;
                break;
            }
            cycle = (int) next;
        }

        Map<FrameState, Integer> packedFrames = new LinkedHashMap<>();
        int[] timeline = new int[cycle];
        for (int tick = 0; tick < cycle; tick++) {
            FrameState state = new FrameState(
                    albedo == null ? 0 : albedo.frameAtTick(tick),
                    normal == null ? 0 : normal.frameAtTick(tick),
                    specular == null ? 0 : specular.frameAtTick(tick)
            );
            Integer packed = packedFrames.get(state);
            if (packed == null) {
                packed = packedFrames.size();
                packedFrames.put(state, packed);
            }
            timeline[tick] = packed;
        }

        return new AnimationPlan(
                List.copyOf(packedFrames.keySet()),
                timeline,
                true,
                interpolate,
                truncated
        );
    }

    static int sampleFrameNearest(
            PbrImage image,
            PbrAnimation animation,
            int sourceFrame,
            float u,
            float v
    ) {
        if (animation == null) {
            return image.sampleNearest(u, v);
        }

        int frameWidth = animation.frameWidth();
        int frameHeight = animation.frameHeight();
        int columns = Math.max(1, image.width() / frameWidth);
        int rows = Math.max(1, image.height() / frameHeight);
        int availableFrames = Math.max(1, columns * rows);
        int frame = Math.floorMod(sourceFrame, availableFrames);
        int frameX = frame % columns;
        int frameY = frame / columns;

        float wrappedU = u - (float) Math.floor(u);
        float wrappedV = v - (float) Math.floor(v);
        int localX = Math.min(frameWidth - 1, (int) (wrappedU * frameWidth));
        int localY = Math.min(frameHeight - 1, (int) (wrappedV * frameHeight));
        return image.pixelArgb(
                frameX * frameWidth + localX,
                frameY * frameHeight + localY
        );
    }

    public static Dimensions boundedDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("texture dimensions must be positive");
        }
        int largest = Math.max(width, height);
        if (largest <= MAX_TEXTURE_DIMENSION) {
            return new Dimensions(width, height);
        }

        float scale = MAX_TEXTURE_DIMENSION / (float) largest;
        int boundedWidth = Math.max(1, Math.round(width * scale));
        int boundedHeight = Math.max(1, Math.round(height * scale));
        return new Dimensions(boundedWidth, boundedHeight);
    }

    private static long lcm(int a, int b) {
        return (long) a / gcd(a, b) * b;
    }

    private static int gcd(int a, int b) {
        int x = Math.max(a, 1);
        int y = Math.max(b, 1);
        while (y != 0) {
            int remainder = x % y;
            x = y;
            y = remainder;
        }
        return x;
    }

    private static void putWord(ByteBuffer buffer, int wordIndex, int value) {
        buffer.putInt(Math.multiplyExact(wordIndex, Integer.BYTES), value);
    }

    public record Dimensions(int width, int height) {
    }

    record FrameState(int albedoFrame, int normalFrame, int specularFrame) {
    }

    record AnimationPlan(
            List<FrameState> states,
            int[] timeline,
            boolean animated,
            boolean interpolateRequested,
            boolean truncated
    ) {
    }

    public record PackResult(
            int textureCount,
            int texelCount,
            int droppedTextures,
            int downsampledTextures,
            int alphaTextures,
            int animatedTextures,
            int interpolatedAnimations,
            int truncatedAnimations,
            int animationTimelineWords,
            int usedWords
    ) {
        public long usedBytes() {
            return (long) usedWords * Integer.BYTES;
        }
    }
}
