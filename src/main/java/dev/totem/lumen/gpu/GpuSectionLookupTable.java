package dev.totem.lumen.gpu;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * CPU mirror of the fixed-size open-addressed section lookup used by Vulkan shaders.
 *
 * <p>Each bucket is four 32-bit words: section x/y/z plus {@code slot + 1}. A zero slot word marks
 * an empty bucket, allowing GPU slot zero to be represented without a separate occupancy bit.</p>
 */
public final class GpuSectionLookupTable {
    public static final int WORDS_PER_BUCKET = 4;

    private final int capacity;
    private final int mask;
    private final int[] words;
    private int size;
    private int maxProbe;

    public GpuSectionLookupTable(int capacity) {
        if (capacity < 2 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two >= 2");
        }
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.words = new int[Math.multiplyExact(capacity, WORDS_PER_BUCKET)];
    }

    public void clear() {
        Arrays.fill(words, 0);
        size = 0;
        maxProbe = 0;
    }

    public int put(int sectionX, int sectionY, int sectionZ, int slot) {
        if (slot < 0) {
            throw new IllegalArgumentException("slot must be >= 0");
        }
        int start = hash(sectionX, sectionY, sectionZ) & mask;
        for (int probe = 0; probe < capacity; probe++) {
            int bucket = (start + probe) & mask;
            int base = bucket * WORDS_PER_BUCKET;
            int slotPlusOne = words[base + 3];
            if (slotPlusOne == 0) {
                words[base] = sectionX;
                words[base + 1] = sectionY;
                words[base + 2] = sectionZ;
                words[base + 3] = Math.addExact(slot, 1);
                size++;
                maxProbe = Math.max(maxProbe, probe);
                return probe;
            }
            if (words[base] == sectionX && words[base + 1] == sectionY && words[base + 2] == sectionZ) {
                words[base + 3] = Math.addExact(slot, 1);
                maxProbe = Math.max(maxProbe, probe);
                return probe;
            }
        }
        throw new IllegalStateException("GPU section lookup table is full: " + capacity);
    }

    public int findSlot(int sectionX, int sectionY, int sectionZ) {
        int start = hash(sectionX, sectionY, sectionZ) & mask;
        for (int probe = 0; probe < capacity; probe++) {
            int bucket = (start + probe) & mask;
            int base = bucket * WORDS_PER_BUCKET;
            int slotPlusOne = words[base + 3];
            if (slotPlusOne == 0) {
                return -1;
            }
            if (words[base] == sectionX && words[base + 1] == sectionY && words[base + 2] == sectionZ) {
                return slotPlusOne - 1;
            }
        }
        return -1;
    }

    public void writeTo(ByteBuffer buffer, int baseWord) {
        int baseByte = Math.multiplyExact(baseWord, Integer.BYTES);
        for (int index = 0; index < words.length; index++) {
            buffer.putInt(baseByte + index * Integer.BYTES, words[index]);
        }
    }

    public int capacity() {
        return capacity;
    }

    public int size() {
        return size;
    }

    public int maxProbe() {
        return maxProbe;
    }

    public int wordCount() {
        return words.length;
    }

    /** Must stay bit-identical to the GLSL lookup hash. Java overflow is intentional. */
    public static int hash(int x, int y, int z) {
        int h = x * 0x9E3779B1;
        h ^= y * 0x85EBCA77;
        h ^= z * 0xC2B2AE3D;
        h ^= h >>> 16;
        return h;
    }
}
