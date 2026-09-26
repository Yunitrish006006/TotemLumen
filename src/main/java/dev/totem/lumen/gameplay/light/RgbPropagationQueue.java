package dev.totem.lumen.gameplay.light;

/** Primitive FIFO for radial RGB propagation metadata on the server hot path. */
final class RgbPropagationQueue {
    private long[] positions = new long[4096];
    private long[] origins = new long[4096];
    private int[] packed = new int[4096];
    private int[] penalties = new int[4096];
    private float[] distances = new float[4096];
    private boolean[] rounded = new boolean[4096];
    private int head;
    private int size;
    private int currentPacked;
    private int currentPenalty;
    private float currentDistance;
    private long currentOrigin;
    private boolean currentRounded;

    void add(long position, int light, float distance, int penalty, long origin, boolean radial) {
        ensureCapacity(size + 1);
        int index = (head + size) & (positions.length - 1);
        positions[index] = position;
        packed[index] = light;
        distances[index] = distance;
        penalties[index] = penalty;
        origins[index] = origin;
        rounded[index] = radial;
        size++;
    }

    long remove() {
        if (size == 0) throw new IllegalStateException("queue is empty");
        int index = head;
        currentPacked = packed[index];
        currentDistance = distances[index];
        currentPenalty = penalties[index];
        currentOrigin = origins[index];
        currentRounded = rounded[index];
        long position = positions[index];
        head = (head + 1) & (positions.length - 1);
        size--;
        return position;
    }

    int packed() { return currentPacked; }
    int penalty() { return currentPenalty; }
    float distance() { return currentDistance; }
    long origin() { return currentOrigin; }
    boolean rounded() { return currentRounded; }
    boolean isEmpty() { return size == 0; }

    private void ensureCapacity(int required) {
        if (required <= positions.length) return;
        int nextLength = positions.length << 1;
        while (nextLength < required) nextLength <<= 1;
        long[] nextPositions = new long[nextLength];
        long[] nextOrigins = new long[nextLength];
        int[] nextPacked = new int[nextLength];
        int[] nextPenalties = new int[nextLength];
        float[] nextDistances = new float[nextLength];
        boolean[] nextRounded = new boolean[nextLength];
        for (int index = 0; index < size; index++) {
            int oldIndex = (head + index) & (positions.length - 1);
            nextPositions[index] = positions[oldIndex];
            nextOrigins[index] = origins[oldIndex];
            nextPacked[index] = packed[oldIndex];
            nextPenalties[index] = penalties[oldIndex];
            nextDistances[index] = distances[oldIndex];
            nextRounded[index] = rounded[oldIndex];
        }
        positions = nextPositions;
        origins = nextOrigins;
        packed = nextPacked;
        penalties = nextPenalties;
        distances = nextDistances;
        rounded = nextRounded;
        head = 0;
    }
}
