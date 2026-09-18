package dev.totem.lumen.gpu;

import dev.totem.lumen.material.PbrImage;
import dev.totem.lumen.material.PbrTextureData;
import dev.totem.lumen.material.PbrTextureHandleRegistry;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Fixed-capacity P18 texture tail.
 *
 * <p>Each resident texel stores three raw ARGB words: albedo, LabPBR normal, LabPBR specular.
 * Missing auxiliary maps remain zero and are distinguished through descriptor flags so shaders can
 * fall back to the Alpha 43 baseline. Albedo alpha is never discarded.</p>
 */
public final class GpuPbrTextureScene {
    public static final int ABI_VERSION = 1;
    public static final int MAX_TEXTURE_HANDLES = PbrTextureHandleRegistry.MAX_HANDLE + 1;
    public static final int MAX_TEXTURE_DIMENSION = 128;
    public static final int MAX_TEXELS = 2_097_152;

    public static final int FLAG_HAS_NORMAL = 1;
    public static final int FLAG_HAS_SPECULAR = 1 << 1;
    public static final int FLAG_HAS_ALPHA = 1 << 2;
    public static final int FLAG_DOWNSAMPLED = 1 << 3;

    public static final int HEADER_WORDS = 8;
    public static final int DESCRIPTOR_WORDS_PER_RECORD = 8;
    public static final int DESCRIPTOR_WORDS =
            MAX_TEXTURE_HANDLES * DESCRIPTOR_WORDS_PER_RECORD;
    public static final int TEXEL_WORDS_PER_RECORD = 3;
    public static final int TEXEL_POOL_WORDS = MAX_TEXELS * TEXEL_WORDS_PER_RECORD;

    public static final int DESCRIPTOR_BASE_WORD = HEADER_WORDS;
    public static final int TEXEL_POOL_BASE_WORD = DESCRIPTOR_BASE_WORD + DESCRIPTOR_WORDS;
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

        // Clear header + all descriptors. Pixel pool is append-only for this packing pass and stale
        // texels are unreachable once their descriptor is zeroed.
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
        int packedTextures = 0;
        int droppedTextures = 0;
        int downsampledTextures = 0;
        int alphaTextures = 0;

        for (PbrTextureData texture : sorted) {
            int handle = PbrTextureHandleRegistry.existingHandle(texture.spriteId());
            if (handle <= 0 || handle >= MAX_TEXTURE_HANDLES) {
                droppedTextures++;
                continue;
            }

            Dimensions dimensions = boundedDimensions(
                    texture.albedo().width(),
                    texture.albedo().height()
            );
            int texelCount = Math.multiplyExact(dimensions.width(), dimensions.height());
            if ((long) nextTexel + texelCount > MAX_TEXELS) {
                droppedTextures++;
                continue;
            }

            boolean hasNormal = texture.hasNormalMap();
            boolean hasSpecular = texture.hasSpecularMap();
            boolean hasAlpha = false;
            boolean downsampled = dimensions.width() != texture.albedo().width()
                    || dimensions.height() != texture.albedo().height();

            int texelBase = baseWord + TEXEL_POOL_BASE_WORD
                    + nextTexel * TEXEL_WORDS_PER_RECORD;
            for (int y = 0; y < dimensions.height(); y++) {
                float v = (y + 0.5f) / dimensions.height();
                for (int x = 0; x < dimensions.width(); x++) {
                    float u = (x + 0.5f) / dimensions.width();
                    int texelIndex = y * dimensions.width() + x;
                    int word = texelBase + texelIndex * TEXEL_WORDS_PER_RECORD;

                    int albedo = texture.albedo().sampleNearest(u, v);
                    if (((albedo >>> 24) & 0xFF) < 255) hasAlpha = true;
                    putWord(buffer, word, albedo);

                    int normal = hasNormal ? texture.normal().sampleNearest(u, v) : 0;
                    int specular = hasSpecular ? texture.specular().sampleNearest(u, v) : 0;
                    putWord(buffer, word + 1, normal);
                    putWord(buffer, word + 2, specular);
                }
            }

            int flags = 0;
            if (hasNormal) flags |= FLAG_HAS_NORMAL;
            if (hasSpecular) flags |= FLAG_HAS_SPECULAR;
            if (hasAlpha) flags |= FLAG_HAS_ALPHA;
            if (downsampled) flags |= FLAG_DOWNSAMPLED;

            int descriptor = baseWord + DESCRIPTOR_BASE_WORD
                    + handle * DESCRIPTOR_WORDS_PER_RECORD;
            putWord(buffer, descriptor, nextTexel);
            putWord(buffer, descriptor + 1, dimensions.width());
            putWord(buffer, descriptor + 2, dimensions.height());
            putWord(buffer, descriptor + 3, flags);
            putWord(buffer, descriptor + 4, texture.albedo().width());
            putWord(buffer, descriptor + 5, texture.albedo().height());
            putWord(buffer, descriptor + 6, texture.spriteId().hashCode());
            putWord(buffer, descriptor + 7, handle);

            nextTexel += texelCount;
            packedTextures++;
            if (downsampled) downsampledTextures++;
            if (hasAlpha) alphaTextures++;
        }

        putWord(buffer, baseWord, ABI_VERSION);
        putWord(buffer, baseWord + 1, packedTextures);
        putWord(buffer, baseWord + 2, nextTexel);
        putWord(buffer, baseWord + 3, droppedTextures);
        putWord(buffer, baseWord + 4, downsampledTextures);
        putWord(buffer, baseWord + 5, alphaTextures);
        putWord(buffer, baseWord + 6, DESCRIPTOR_BASE_WORD);
        putWord(buffer, baseWord + 7, TEXEL_POOL_BASE_WORD);

        int usedWords = TEXEL_POOL_BASE_WORD
                + nextTexel * TEXEL_WORDS_PER_RECORD;
        return new PackResult(
                packedTextures,
                nextTexel,
                droppedTextures,
                downsampledTextures,
                alphaTextures,
                usedWords
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

    private static void putWord(ByteBuffer buffer, int wordIndex, int value) {
        buffer.putInt(Math.multiplyExact(wordIndex, Integer.BYTES), value);
    }

    public record Dimensions(int width, int height) {
    }

    public record PackResult(
            int textureCount,
            int texelCount,
            int droppedTextures,
            int downsampledTextures,
            int alphaTextures,
            int usedWords
    ) {
        public long usedBytes() {
            return (long) usedWords * Integer.BYTES;
        }
    }
}
