package dev.totem.lumen.gpu;

import dev.totem.lumen.material.PbrImage;
import dev.totem.lumen.scene.DynamicEntityBroadPhase;
import dev.totem.lumen.scene.DynamicEntitySnapshot;
import dev.totem.lumen.scene.SectionKey;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CPU mirror/packer for the bounded P17 dynamic-entity scene tail.
 *
 * <p>Absolute entity coordinates remain double precision in the CPU snapshot. The GPU ABI stores
 * an integer section origin plus section-local floats so large world coordinates do not lose
 * precision before tracing.</p>
 */
public final class GpuDynamicEntityScene {
    public static final int ABI_VERSION = 2;
    public static final int MAX_ENTITIES = 256;
    public static final int MAX_ENTITY_QUADS = 65_536;
    public static final int SECTION_LOOKUP_CAPACITY = 512;
    public static final int MAX_ENTITIES_PER_SECTION = DynamicEntityBroadPhase.DEFAULT_MAX_ENTITIES_PER_SECTION;

    public static final int FLAG_SPIDER_EYES = 1;
    public static final int MAX_SPIDER_EYE_DIMENSION = 128;
    public static final int SPIDER_EYE_POOL_WORDS =
            MAX_SPIDER_EYE_DIMENSION * MAX_SPIDER_EYE_DIMENSION;

    public static final int HEADER_WORDS = 16;
    public static final int ENTITY_DESCRIPTOR_WORDS_PER_RECORD = 24;
    public static final int ENTITY_DESCRIPTOR_WORDS = MAX_ENTITIES * ENTITY_DESCRIPTOR_WORDS_PER_RECORD;
    public static final int SECTION_BUCKET_WORDS = 4 + MAX_ENTITIES_PER_SECTION;
    public static final int SECTION_LOOKUP_WORDS = SECTION_LOOKUP_CAPACITY * SECTION_BUCKET_WORDS;
    public static final int QUAD_WORDS_PER_RECORD = 20;
    public static final int QUAD_POOL_WORDS = MAX_ENTITY_QUADS * QUAD_WORDS_PER_RECORD;

    public static final int ENTITY_DESCRIPTOR_BASE_WORD = HEADER_WORDS;
    public static final int SECTION_LOOKUP_BASE_WORD = ENTITY_DESCRIPTOR_BASE_WORD + ENTITY_DESCRIPTOR_WORDS;
    public static final int SPIDER_EYE_POOL_BASE_WORD = SECTION_LOOKUP_BASE_WORD + SECTION_LOOKUP_WORDS;
    public static final int QUAD_POOL_BASE_WORD = SPIDER_EYE_POOL_BASE_WORD + SPIDER_EYE_POOL_WORDS;
    public static final int MAX_STORAGE_WORDS = QUAD_POOL_BASE_WORD + QUAD_POOL_WORDS;
    public static final long MAX_STORAGE_BYTES = (long) MAX_STORAGE_WORDS * Integer.BYTES;

    private static final int SECTION_MASK = SECTION_LOOKUP_CAPACITY - 1;
    private static final int SECTION_SIZE = 16;

    private GpuDynamicEntityScene() {
    }

    /** Packs one current-dimension entity scene into {@code buffer} starting at {@code baseWord}. */
    public static PackResult pack(
            ByteBuffer buffer,
            int baseWord,
            List<DynamicEntitySnapshot> entities
    ) {
        return pack(buffer, baseWord, entities, null);
    }

    public static PackResult pack(
            ByteBuffer buffer,
            int baseWord,
            List<DynamicEntitySnapshot> entities,
            PbrImage spiderEyeTexture
    ) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(entities, "entities");
        if (baseWord < 0) throw new IllegalArgumentException("baseWord must be >= 0");
        if (entities.size() > MAX_ENTITIES) {
            throw new IllegalStateException(
                    "P17 dynamic entity capacity exceeded: entities=" + entities.size()
                            + ", max=" + MAX_ENTITIES
            );
        }

        long maxEndBytes = ((long) baseWord + MAX_STORAGE_WORDS) * Integer.BYTES;
        if (maxEndBytes > buffer.capacity()) {
            throw new IllegalStateException(
                    "P17 entity storage exceeds scene buffer: requiredCapacity=" + maxEndBytes
                            + ", capacity=" + buffer.capacity()
            );
        }

        String dimensionId = null;
        int totalQuads = 0;
        for (DynamicEntitySnapshot entity : entities) {
            Objects.requireNonNull(entity, "entity");
            if (dimensionId == null) {
                dimensionId = entity.dimensionId();
            } else if (!dimensionId.equals(entity.dimensionId())) {
                throw new IllegalArgumentException(
                        "P17 GPU scene must contain exactly one dimension; found "
                                + dimensionId + " and " + entity.dimensionId()
                );
            }
            totalQuads = Math.addExact(totalQuads, entity.quadCount());
            if (totalQuads > MAX_ENTITY_QUADS) {
                throw new IllegalStateException(
                        "P17 dynamic quad capacity exceeded: quads=" + totalQuads
                                + ", max=" + MAX_ENTITY_QUADS
                );
            }
        }

        // Clear all fixed metadata because despawns/movement can leave holes in descriptors/buckets.
        int fixedWords = QUAD_POOL_BASE_WORD;
        for (int word = 0; word < fixedWords; word++) {
            putWord(buffer, baseWord + word, 0);
        }

        int nextQuad = 0;
        for (int entityIndex = 0; entityIndex < entities.size(); entityIndex++) {
            DynamicEntitySnapshot entity = entities.get(entityIndex);
            int originSectionX = worldToSection(entity.worldX());
            int originSectionY = worldToSection(entity.worldY());
            int originSectionZ = worldToSection(entity.worldZ());
            double sectionOriginX = (double) originSectionX * SECTION_SIZE;
            double sectionOriginY = (double) originSectionY * SECTION_SIZE;
            double sectionOriginZ = (double) originSectionZ * SECTION_SIZE;

            int descriptor = baseWord + ENTITY_DESCRIPTOR_BASE_WORD
                    + entityIndex * ENTITY_DESCRIPTOR_WORDS_PER_RECORD;
            long instanceId = entity.instanceId();
            putWord(buffer, descriptor, (int) instanceId);
            putWord(buffer, descriptor + 1, (int) (instanceId >>> 32));
            putWord(buffer, descriptor + 2, originSectionX);
            putWord(buffer, descriptor + 3, originSectionY);
            putWord(buffer, descriptor + 4, originSectionZ);
            putFloat(buffer, descriptor + 5, entity.worldX() - sectionOriginX);
            putFloat(buffer, descriptor + 6, entity.worldY() - sectionOriginY);
            putFloat(buffer, descriptor + 7, entity.worldZ() - sectionOriginZ);
            putFloat(buffer, descriptor + 8, entity.minX() - sectionOriginX);
            putFloat(buffer, descriptor + 9, entity.minY() - sectionOriginY);
            putFloat(buffer, descriptor + 10, entity.minZ() - sectionOriginZ);
            putFloat(buffer, descriptor + 11, entity.maxX() - sectionOriginX);
            putFloat(buffer, descriptor + 12, entity.maxY() - sectionOriginY);
            putFloat(buffer, descriptor + 13, entity.maxZ() - sectionOriginZ);
            putWord(buffer, descriptor + 14, nextQuad);
            putWord(buffer, descriptor + 15, entity.quadCount());
            putWord(buffer, descriptor + 16, entityFlags(entity.entityTypeId()));
            for (int reserved = 17; reserved < ENTITY_DESCRIPTOR_WORDS_PER_RECORD; reserved++) {
                putWord(buffer, descriptor + reserved, 0);
            }

            float[] positions = entity.copyQuadPositions();
            float[] uvs = entity.copyQuadUvs();
            for (int quad = 0; quad < entity.quadCount(); quad++) {
                int quadWord = baseWord + QUAD_POOL_BASE_WORD
                        + (nextQuad + quad) * QUAD_WORDS_PER_RECORD;
                int positionBase = quad * 12;
                for (int word = 0; word < 12; word++) {
                    putWord(
                            buffer,
                            quadWord + word,
                            Float.floatToRawIntBits(positions[positionBase + word])
                    );
                }
                int uvBase = quad * 8;
                for (int word = 0; word < 8; word++) {
                    putWord(
                            buffer,
                            quadWord + 12 + word,
                            Float.floatToRawIntBits(uvs[uvBase + word])
                    );
                }
            }
            nextQuad += entity.quadCount();
        }

        DynamicEntityBroadPhase broadPhase = DynamicEntityBroadPhase.build(entities);
        List<Map.Entry<SectionKey, int[]>> buckets = new ArrayList<>(
                broadPhase.copyBuckets().entrySet()
        );
        buckets.sort(Comparator
                .comparingInt((Map.Entry<SectionKey, int[]> entry) -> entry.getKey().x())
                .thenComparingInt(entry -> entry.getKey().y())
                .thenComparingInt(entry -> entry.getKey().z()));

        int maxProbe = 0;
        for (Map.Entry<SectionKey, int[]> entry : buckets) {
            SectionKey key = entry.getKey();
            int[] indices = entry.getValue();
            int probe = insertSectionBucket(buffer, baseWord, key, indices);
            maxProbe = Math.max(maxProbe, probe);
        }

        Dimensions spiderEyes = packSpiderEyeTexture(buffer, baseWord, spiderEyeTexture);

        putWord(buffer, baseWord, ABI_VERSION);
        putWord(buffer, baseWord + 1, entities.size());
        putWord(buffer, baseWord + 2, totalQuads);
        putWord(buffer, baseWord + 3, buckets.size());
        putWord(buffer, baseWord + 4, broadPhase.overflowAssignments());
        putWord(buffer, baseWord + 5, maxProbe);
        putWord(buffer, baseWord + 6, ENTITY_DESCRIPTOR_BASE_WORD);
        putWord(buffer, baseWord + 7, SECTION_LOOKUP_BASE_WORD);
        putWord(buffer, baseWord + 8, spiderEyes.width());
        putWord(buffer, baseWord + 9, spiderEyes.height());
        putWord(buffer, baseWord + 10, spiderEyes.width() * spiderEyes.height());
        putWord(buffer, baseWord + 11, SPIDER_EYE_POOL_BASE_WORD);
        putWord(buffer, baseWord + 12, spiderEyes.width() > 0 ? 1 : 0);
        putWord(buffer, baseWord + 13, 0);
        putWord(buffer, baseWord + 14, 0);
        putWord(buffer, baseWord + 15, 0);

        int usedWords = Math.addExact(
                QUAD_POOL_BASE_WORD,
                Math.multiplyExact(totalQuads, QUAD_WORDS_PER_RECORD)
        );
        return new PackResult(
                entities.size(),
                totalQuads,
                buckets.size(),
                broadPhase.overflowAssignments(),
                maxProbe,
                usedWords
        );
    }

    private static int entityFlags(String entityTypeId) {
        return entityTypeId.equals("minecraft:spider")
                || entityTypeId.equals("minecraft:cave_spider")
                ? FLAG_SPIDER_EYES
                : 0;
    }

    private static Dimensions packSpiderEyeTexture(
            ByteBuffer buffer,
            int baseWord,
            PbrImage image
    ) {
        if (image == null) return new Dimensions(0, 0);

        int largest = Math.max(image.width(), image.height());
        float scale = largest <= MAX_SPIDER_EYE_DIMENSION
                ? 1.0f
                : MAX_SPIDER_EYE_DIMENSION / (float) largest;
        int width = Math.max(1, Math.round(image.width() * scale));
        int height = Math.max(1, Math.round(image.height() * scale));

        for (int y = 0; y < height; y++) {
            float v = (y + 0.5f) / height;
            for (int x = 0; x < width; x++) {
                float u = (x + 0.5f) / width;
                putWord(
                        buffer,
                        baseWord + SPIDER_EYE_POOL_BASE_WORD + y * width + x,
                        image.sampleNearest(u, v)
                );
            }
        }
        return new Dimensions(width, height);
    }

    /** Returns the entity index for a packed section candidate, or -1 when absent. Test/debug helper. */
    public static int packedSectionCandidate(
            ByteBuffer buffer,
            int baseWord,
            int sectionX,
            int sectionY,
            int sectionZ,
            int candidateOffset
    ) {
        if (candidateOffset < 0 || candidateOffset >= MAX_ENTITIES_PER_SECTION) return -1;
        int start = GpuSectionLookupTable.hash(sectionX, sectionY, sectionZ) & SECTION_MASK;
        for (int probe = 0; probe < SECTION_LOOKUP_CAPACITY; probe++) {
            int bucket = (start + probe) & SECTION_MASK;
            int bucketWord = baseWord + SECTION_LOOKUP_BASE_WORD + bucket * SECTION_BUCKET_WORDS;
            int count = getWord(buffer, bucketWord + 3);
            if (count == 0) return -1;
            if (getWord(buffer, bucketWord) == sectionX
                    && getWord(buffer, bucketWord + 1) == sectionY
                    && getWord(buffer, bucketWord + 2) == sectionZ) {
                if (candidateOffset >= count) return -1;
                return getWord(buffer, bucketWord + 4 + candidateOffset);
            }
        }
        return -1;
    }

    private static int insertSectionBucket(
            ByteBuffer buffer,
            int baseWord,
            SectionKey key,
            int[] entityIndices
    ) {
        if (entityIndices.length == 0) return 0;
        if (entityIndices.length > MAX_ENTITIES_PER_SECTION) {
            throw new IllegalArgumentException("CPU broad phase returned an oversized P17 bucket");
        }

        int start = GpuSectionLookupTable.hash(key.x(), key.y(), key.z()) & SECTION_MASK;
        for (int probe = 0; probe < SECTION_LOOKUP_CAPACITY; probe++) {
            int bucket = (start + probe) & SECTION_MASK;
            int bucketWord = baseWord + SECTION_LOOKUP_BASE_WORD + bucket * SECTION_BUCKET_WORDS;
            int count = getWord(buffer, bucketWord + 3);
            if (count == 0) {
                putWord(buffer, bucketWord, key.x());
                putWord(buffer, bucketWord + 1, key.y());
                putWord(buffer, bucketWord + 2, key.z());
                putWord(buffer, bucketWord + 3, entityIndices.length);
                for (int index = 0; index < entityIndices.length; index++) {
                    putWord(buffer, bucketWord + 4 + index, entityIndices[index]);
                }
                return probe;
            }
        }
        throw new IllegalStateException(
                "P17 section lookup table is full: capacity=" + SECTION_LOOKUP_CAPACITY
        );
    }

    private static int worldToSection(double coordinate) {
        long block = (long) Math.floor(coordinate);
        long section = Math.floorDiv(block, SECTION_SIZE);
        if (section < Integer.MIN_VALUE || section > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("P17 entity section coordinate exceeds integer range");
        }
        return (int) section;
    }

    private static void putFloat(ByteBuffer buffer, int wordIndex, double value) {
        if (!Double.isFinite(value) || value < -Float.MAX_VALUE || value > Float.MAX_VALUE) {
            throw new IllegalArgumentException("P17 section-relative value cannot be represented as float: " + value);
        }
        putWord(buffer, wordIndex, Float.floatToRawIntBits((float) value));
    }

    private static void putWord(ByteBuffer buffer, int wordIndex, int value) {
        buffer.putInt(Math.multiplyExact(wordIndex, Integer.BYTES), value);
    }

    private static int getWord(ByteBuffer buffer, int wordIndex) {
        return buffer.getInt(Math.multiplyExact(wordIndex, Integer.BYTES));
    }

    private record Dimensions(int width, int height) {
    }

    public record PackResult(
            int entityCount,
            int totalQuads,
            int sectionBucketCount,
            int overflowAssignments,
            int maxProbe,
            int usedWords
    ) {
        public long usedBytes() {
            return (long) usedWords * Integer.BYTES;
        }
    }
}
