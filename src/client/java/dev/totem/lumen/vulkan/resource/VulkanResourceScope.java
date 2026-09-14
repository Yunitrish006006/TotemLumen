package dev.totem.lumen.vulkan.resource;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Owns Totem Lumen Vulkan resources and releases them in reverse creation order.
 * Minecraft-owned Vulkan objects must never be registered here.
 */
public final class VulkanResourceScope implements AutoCloseable {
    private final Deque<AutoCloseable> resources = new ArrayDeque<>();
    private boolean closed;

    public synchronized <T extends AutoCloseable> T own(T resource) {
        if (closed) {
            try {
                resource.close();
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to close resource after scope shutdown", exception);
            }
            throw new IllegalStateException("Vulkan resource scope is already closed");
        }
        resources.push(resource);
        return resource;
    }

    public synchronized int resourceCount() {
        return resources.size();
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;

        RuntimeException failure = null;
        while (!resources.isEmpty()) {
            try {
                resources.pop().close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = new RuntimeException("Failed to release one or more Vulkan resources");
                }
                failure.addSuppressed(exception);
            }
        }

        if (failure != null) {
            throw failure;
        }
    }
}
