package dev.totem.lumen.vulkan.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class VulkanWorkerLifetimeTest {
    @Test
    void deviceDestructionWaitsForNativeCallAndItsCleanup() throws Exception {
        var lifetime = new VulkanWorkerLifetime();
        var worker = lifetime.tryAcquire();
        assertNotNull(worker);
        var closing = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var destroyed = executor.submit(() -> {
                assertEquals(1, lifetime.stopAccepting());
                closing.countDown();
                lifetime.awaitCompletion();
                return true;
            });
            try {
                assertTrue(closing.await(1, TimeUnit.SECONDS));
                assertNull(lifetime.tryAcquire());
                assertThrows(TimeoutException.class, () -> destroyed.get(100, TimeUnit.MILLISECONDS));
            } finally {
                worker.close();
            }
            assertTrue(destroyed.get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void interruptedShutdownStillWaitsAndRestoresInterruptFlag() throws Exception {
        var lifetime = new VulkanWorkerLifetime();
        var worker = lifetime.tryAcquire();
        var closing = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var destroyed = executor.submit(() -> {
                lifetime.stopAccepting();
                Thread.currentThread().interrupt();
                closing.countDown();
                lifetime.awaitCompletion();
                return Thread.currentThread().isInterrupted();
            });
            try {
                assertTrue(closing.await(1, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> destroyed.get(100, TimeUnit.MILLISECONDS));
            } finally {
                worker.close();
            }
            assertTrue(destroyed.get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void leaseReservedBeforeThreadStartAlsoPreventsDeviceDestruction() {
        var lifetime = new VulkanWorkerLifetime();
        var first = lifetime.tryAcquire();
        var notStartedYet = lifetime.tryAcquire();
        assertEquals(2, lifetime.stopAccepting());
        first.close();
        first.close();
        assertEquals(1, lifetime.stopAccepting());
        notStartedYet.close();
        assertEquals(0, lifetime.stopAccepting());
        lifetime.awaitCompletion();
        assertNull(lifetime.tryAcquire());
    }

    @Test
    void waitingWithoutStoppingNewWorkIsRejected() {
        var lifetime = new VulkanWorkerLifetime();
        assertThrows(IllegalStateException.class, lifetime::awaitCompletion);
    }
}
