package dev.totem.lumen.vulkan.resource;

/** Stops new device work and quiesces existing native calls before device destruction. */
public final class VulkanWorkerLifetime {
    private boolean closing;
    private int active;

    public synchronized Lease tryAcquire() {
        if (closing) return null;
        active++;
        return new Lease();
    }

    public synchronized int stopAccepting() {
        closing = true;
        return active;
    }

    /** A timeout or interrupt must never allow device destruction during a native call. */
    public void awaitCompletion() {
        boolean interrupted = false;
        synchronized (this) {
            if (!closing) throw new IllegalStateException("Device work must be stopped before waiting");
            while (active != 0) {
                try {
                    wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    public final class Lease implements AutoCloseable {
        private boolean released;

        private Lease() {}

        @Override
        public void close() {
            synchronized (VulkanWorkerLifetime.this) {
                if (released) return;
                released = true;
                active--;
                VulkanWorkerLifetime.this.notifyAll();
            }
        }
    }
}
