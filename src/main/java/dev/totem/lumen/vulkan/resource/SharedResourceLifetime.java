package dev.totem.lumen.vulkan.resource;

import java.util.Objects;

/** Retains shared native objects until their owner and every GPU-completed binding retire. */
public final class SharedResourceLifetime implements AutoCloseable {
    private final Runnable destroy;
    private int references = 1;
    private boolean retired;

    public SharedResourceLifetime(Runnable destroy) {
        this.destroy = Objects.requireNonNull(destroy);
    }

    public synchronized Lease retain() {
        if (retired) throw new IllegalStateException("Shared resource is retired");
        references++;
        return new Lease();
    }

    @Override
    public synchronized void close() {
        if (retired) return;
        retired = true;
        release();
    }

    private void release() {
        if (--references == 0) destroy.run();
    }

    public final class Lease implements AutoCloseable {
        private boolean released;

        private Lease() {}

        @Override
        public void close() {
            synchronized (SharedResourceLifetime.this) {
                if (released) return;
                released = true;
                release();
            }
        }
    }
}
