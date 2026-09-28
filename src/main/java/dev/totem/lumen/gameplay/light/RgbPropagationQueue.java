package dev.totem.lumen.gameplay.light;

/** Primitive FIFO for deterministic Minecraft-like RGB propagation on the server hot path. */
final class RgbPropagationQueue {
    private long[] positions = new long[4096];
    private int[] packed = new int[4096];
    private int head;
    private int size;
    private int currentPacked;

    void add(long position, int light) {
        ensureCapacity(size + 1);
        int index = (head + size) & (positions.length - 1);
        positions[index] = position;
        packed[index] = light;
        size++;
    }

    long remove() {
        if (size == 0) throw new IllegalStateException("queue is empty");
        int index = head;
        currentPacked = packed[index];
        long position = positions[index];
        head = (head + 1) & (positions.length - 1);
        size--;
        return position;
    }

    int packed() { return currentPacked; }
    boolean isEmpty() { return size == 0; }

    private void ensureCapacity(int required) {
        if (required <= positions.length) return;
        int nextLength = positions.length << 1;
        while (nextLength < required) nextLength <<= 1;
        long[] nextPositions = new long[nextLength];
        int[] nextPacked = new int[nextLength];
        for (int index = 0; index < size; index++) {
            int oldIndex = (head + index) & (positions.length - 1);
            nextPositions[index] = positions[oldIndex];
            nextPacked[index] = packed[oldIndex];
        }
        positions = nextPositions;
        packed = nextPacked;
        head = 0;
    }
}
