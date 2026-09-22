package dev.totem.lumen.gpu;

import dev.totem.lumen.scene.FluidGeometrySnapshot;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

/** CPU mirror/packer for the bounded P14E exact-fluid scene tail. */
public final class GpuFluidScene {
    public static final int ABI_VERSION = 2;
    public static final int MAX_FLUID_CELLS = 16_384;
    public static final int MAX_FLUID_QUADS = 65_536;
    public static final int LOOKUP_CAPACITY = 32_768;

    public static final int FLUID_KIND_OTHER = 0;
    public static final int FLUID_KIND_WATER = 1;
    public static final int FLUID_KIND_LAVA = 2;
    public static final int CELL_FLAG_FLUID_ONLY = 1;

    public static final int HEADER_WORDS = 8;
    public static final int CELL_DESCRIPTOR_WORDS_PER_RECORD = 9;
    public static final int CELL_DESCRIPTOR_WORDS = MAX_FLUID_CELLS * CELL_DESCRIPTOR_WORDS_PER_RECORD;
    public static final int LOOKUP_WORDS_PER_BUCKET = 4;
    public static final int LOOKUP_WORDS = LOOKUP_CAPACITY * LOOKUP_WORDS_PER_BUCKET;
    public static final int QUAD_WORDS_PER_RECORD = 14;
    public static final int QUAD_POOL_WORDS = MAX_FLUID_QUADS * QUAD_WORDS_PER_RECORD;

    public static final int CELL_DESCRIPTOR_BASE_WORD = HEADER_WORDS;
    public static final int LOOKUP_BASE_WORD = CELL_DESCRIPTOR_BASE_WORD + CELL_DESCRIPTOR_WORDS;
    public static final int QUAD_POOL_BASE_WORD = LOOKUP_BASE_WORD + LOOKUP_WORDS;
    public static final int MAX_STORAGE_WORDS = QUAD_POOL_BASE_WORD + QUAD_POOL_WORDS;
    public static final long MAX_STORAGE_BYTES = (long) MAX_STORAGE_WORDS * Integer.BYTES;

    private static final int LOOKUP_MASK = LOOKUP_CAPACITY - 1;

    private GpuFluidScene() {
    }

    public static PackResult pack(ByteBuffer buffer, int baseWord, List<FluidGeometrySnapshot> fluids) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(fluids, "fluids");
        if (baseWord < 0) throw new IllegalArgumentException("baseWord must be >= 0");
        if (fluids.size() > MAX_FLUID_CELLS) {
            throw new IllegalStateException(
                    "P14E fluid cell capacity exceeded: cells=" + fluids.size()
                            + ", max=" + MAX_FLUID_CELLS
            );
        }

        long requiredCapacity = ((long) baseWord + MAX_STORAGE_WORDS) * Integer.BYTES;
        if (requiredCapacity > buffer.capacity()) {
            throw new IllegalStateException(
                    "P14E fluid storage exceeds scene buffer: requiredCapacity=" + requiredCapacity
                            + ", capacity=" + buffer.capacity()
            );
        }

        String dimensionId = null;
        int totalQuads = 0;
        for (FluidGeometrySnapshot fluid : fluids) {
            Objects.requireNonNull(fluid, "fluid");
            if (dimensionId == null) dimensionId = fluid.dimensionId();
            else if (!dimensionId.equals(fluid.dimensionId())) {
                throw new IllegalArgumentException(
                        "P14E GPU scene must contain one dimension; found "
                                + dimensionId + " and " + fluid.dimensionId()
                );
            }
            totalQuads = Math.addExact(totalQuads, fluid.quadCount());
            if (totalQuads > MAX_FLUID_QUADS) {
                throw new IllegalStateException(
                        "P14E fluid quad capacity exceeded: quads=" + totalQuads
                                + ", max=" + MAX_FLUID_QUADS
                );
            }
        }

        // Clear fixed descriptors and lookup buckets so removed/flowing cells cannot leave stale hits.
        for (int word = 0; word < QUAD_POOL_BASE_WORD; word++) {
            putWord(buffer, baseWord + word, 0);
        }

        int nextQuad = 0;
        int maxProbe = 0;
        for (int cellIndex = 0; cellIndex < fluids.size(); cellIndex++) {
            FluidGeometrySnapshot fluid = fluids.get(cellIndex);
            int descriptor = baseWord + CELL_DESCRIPTOR_BASE_WORD
                    + cellIndex * CELL_DESCRIPTOR_WORDS_PER_RECORD;
            putWord(buffer, descriptor, fluid.blockX());
            putWord(buffer, descriptor + 1, fluid.blockY());
            putWord(buffer, descriptor + 2, fluid.blockZ());
            putWord(buffer, descriptor + 3, fluidKind(fluid.fluidTypeId()));
            putWord(buffer, descriptor + 4, fluid.fluidTypeId().hashCode());
            putWord(buffer, descriptor + 5, nextQuad);
            putWord(buffer, descriptor + 6, fluid.quadCount());
            putWord(buffer, descriptor + 7, fluid.fluidOnlyCell() ? CELL_FLAG_FLUID_ONLY : 0);
            putWord(buffer, descriptor + 8, fluid.tintArgb());

            float[] positions = fluid.copyQuadPositions();
            int[] colors = fluid.copyQuadColors();
            boolean[] doubleSided = fluid.copyDoubleSided();
            for (int quad = 0; quad < fluid.quadCount(); quad++) {
                int quadWord = baseWord + QUAD_POOL_BASE_WORD
                        + (nextQuad + quad) * QUAD_WORDS_PER_RECORD;
                int positionOffset = quad * 12;
                for (int i = 0; i < 12; i++) {
                    putWord(buffer, quadWord + i, Float.floatToRawIntBits(positions[positionOffset + i]));
                }
                putWord(buffer, quadWord + 12, colors[quad]);
                putWord(buffer, quadWord + 13, doubleSided[quad] ? 1 : 0);
            }

            int probe = insertLookup(
                    buffer,
                    baseWord,
                    fluid.blockX(),
                    fluid.blockY(),
                    fluid.blockZ(),
                    cellIndex
            );
            maxProbe = Math.max(maxProbe, probe);
            nextQuad += fluid.quadCount();
        }

        putWord(buffer, baseWord, ABI_VERSION);
        putWord(buffer, baseWord + 1, fluids.size());
        putWord(buffer, baseWord + 2, totalQuads);
        putWord(buffer, baseWord + 3, maxProbe);
        putWord(buffer, baseWord + 4, CELL_DESCRIPTOR_BASE_WORD);
        putWord(buffer, baseWord + 5, LOOKUP_BASE_WORD);
        putWord(buffer, baseWord + 6, QUAD_POOL_BASE_WORD);
        putWord(buffer, baseWord + 7, 0);

        int usedWords = Math.addExact(
                QUAD_POOL_BASE_WORD,
                Math.multiplyExact(totalQuads, QUAD_WORDS_PER_RECORD)
        );
        return new PackResult(fluids.size(), totalQuads, maxProbe, usedWords);
    }

    /** Returns a packed fluid descriptor index for one world block, or -1 when absent. */
    public static int packedFluidIndex(ByteBuffer buffer, int baseWord, int blockX, int blockY, int blockZ) {
        int start = GpuSectionLookupTable.hash(blockX, blockY, blockZ) & LOOKUP_MASK;
        for (int probe = 0; probe < LOOKUP_CAPACITY; probe++) {
            int bucket = (start + probe) & LOOKUP_MASK;
            int bucketWord = baseWord + LOOKUP_BASE_WORD + bucket * LOOKUP_WORDS_PER_BUCKET;
            int indexPlusOne = getWord(buffer, bucketWord + 3);
            if (indexPlusOne == 0) return -1;
            if (getWord(buffer, bucketWord) == blockX
                    && getWord(buffer, bucketWord + 1) == blockY
                    && getWord(buffer, bucketWord + 2) == blockZ) {
                return indexPlusOne - 1;
            }
        }
        return -1;
    }

    public static int fluidKind(String fluidTypeId) {
        return switch (fluidTypeId) {
            case "minecraft:water", "minecraft:flowing_water" -> FLUID_KIND_WATER;
            case "minecraft:lava", "minecraft:flowing_lava" -> FLUID_KIND_LAVA;
            default -> FLUID_KIND_OTHER;
        };
    }

    private static int insertLookup(
            ByteBuffer buffer,
            int baseWord,
            int blockX,
            int blockY,
            int blockZ,
            int cellIndex
    ) {
        int start = GpuSectionLookupTable.hash(blockX, blockY, blockZ) & LOOKUP_MASK;
        for (int probe = 0; probe < LOOKUP_CAPACITY; probe++) {
            int bucket = (start + probe) & LOOKUP_MASK;
            int bucketWord = baseWord + LOOKUP_BASE_WORD + bucket * LOOKUP_WORDS_PER_BUCKET;
            if (getWord(buffer, bucketWord + 3) == 0) {
                putWord(buffer, bucketWord, blockX);
                putWord(buffer, bucketWord + 1, blockY);
                putWord(buffer, bucketWord + 2, blockZ);
                putWord(buffer, bucketWord + 3, cellIndex + 1);
                return probe;
            }
        }
        throw new IllegalStateException("P14E fluid lookup table is full: capacity=" + LOOKUP_CAPACITY);
    }

    private static void putWord(ByteBuffer buffer, int wordIndex, int value) {
        buffer.putInt(Math.multiplyExact(wordIndex, Integer.BYTES), value);
    }

    private static int getWord(ByteBuffer buffer, int wordIndex) {
        return buffer.getInt(Math.multiplyExact(wordIndex, Integer.BYTES));
    }

    public record PackResult(int cellCount, int totalQuads, int maxProbe, int usedWords) {
        public long usedBytes() {
            return (long) usedWords * Integer.BYTES;
        }
    }
}
