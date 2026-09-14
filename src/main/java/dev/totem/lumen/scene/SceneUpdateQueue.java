package dev.totem.lumen.scene;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe handoff from Fabric/Minecraft extraction callbacks to Totem Lumen's owned scene.
 */
public final class SceneUpdateQueue {
    private static final int DEFAULT_MAX_PENDING = 16_384;

    private final ConcurrentLinkedQueue<SceneUpdate> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicLong nextSequence = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final int maxPending;

    public SceneUpdateQueue() {
        this(DEFAULT_MAX_PENDING);
    }

    SceneUpdateQueue(int maxPending) {
        if (maxPending < 1) {
            throw new IllegalArgumentException("maxPending must be positive");
        }
        this.maxPending = maxPending;
    }

    public long nextSequence() {
        return nextSequence.incrementAndGet();
    }

    public boolean offer(SceneUpdate update) {
        while (true) {
            int current = pending.get();
            if (current >= maxPending) {
                dropped.incrementAndGet();
                return false;
            }
            if (pending.compareAndSet(current, current + 1)) {
                queue.offer(update);
                return true;
            }
        }
    }

    public SceneUpdate poll() {
        SceneUpdate update = queue.poll();
        if (update != null) {
            pending.decrementAndGet();
        }
        return update;
    }

    public int pendingCount() {
        return pending.get();
    }

    public long droppedCount() {
        return dropped.get();
    }

    public void clear() {
        while (poll() != null) {
            // Drain without exposing stale updates to a newly attached level.
        }
    }
}
