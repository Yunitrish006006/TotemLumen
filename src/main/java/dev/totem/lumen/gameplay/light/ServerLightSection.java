package dev.totem.lumen.gameplay.light;

import java.util.Arrays;

/** Dense packed RGB storage allocated only for sections that actually receive block light. */
final class ServerLightSection {
    static final int SIZE = 16;
    static final int VOXEL_COUNT = SIZE * SIZE * SIZE;

    private final char[] values = new char[VOXEL_COUNT];
    private int nonZeroCount;

    int get(int localX, int localY, int localZ) {
        return values[index(localX, localY, localZ)];
    }

    boolean set(int localX, int localY, int localZ, int packed) {
        int index = index(localX, localY, localZ);
        char next = (char) packed;
        char previous = values[index];
        if (previous == next) {
            return false;
        }
        if (previous == 0 && next != 0) nonZeroCount++;
        if (previous != 0 && next == 0) nonZeroCount--;
        values[index] = next;
        return true;
    }

    void clear() {
        Arrays.fill(values, (char) 0);
        nonZeroCount = 0;
    }

    boolean isEmpty() {
        return nonZeroCount == 0;
    }

    char[] copyValues() {
        return values.clone();
    }

    static int index(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }
}
