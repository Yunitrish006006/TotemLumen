package dev.totem.lumen.gameplay.light;

/** Small primitive FIFO used by RGB propagation to avoid Long boxing on the server hot path. */
final class LongQueue {
    private long[] values = new long[4096];
    private int head;
    private int size;

    void add(long value) {
        ensureCapacity(size + 1);
        values[(head + size) & (values.length - 1)] = value;
        size++;
    }

    long remove() {
        if (size == 0) {
            throw new IllegalStateException("queue is empty");
        }
        long value = values[head];
        head = (head + 1) & (values.length - 1);
        size--;
        return value;
    }

    boolean isEmpty() {
        return size == 0;
    }

    int size() {
        return size;
    }

    void clear() {
        head = 0;
        size = 0;
    }

    private void ensureCapacity(int required) {
        if (required <= values.length) {
            return;
        }
        int nextLength = values.length << 1;
        while (nextLength < required) {
            nextLength <<= 1;
        }
        long[] next = new long[nextLength];
        for (int index = 0; index < size; index++) {
            next[index] = values[(head + index) & (values.length - 1)];
        }
        values = next;
        head = 0;
    }
}
