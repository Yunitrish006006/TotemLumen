package dev.totem.lumen.vulkan.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SharedResourceLifetimeTest {
    @Test
    void shutdownRetainsPipelineUntilAllSubmittedBindingsComplete() {
        AtomicInteger destroys = new AtomicInteger();
        var owner = new SharedResourceLifetime(destroys::incrementAndGet);
        var oldScene = owner.retain();
        var resizedScene = owner.retain();
        owner.close();
        assertThrows(IllegalStateException.class, owner::retain);
        assertEquals(0, destroys.get());
        resizedScene.close();
        assertEquals(0, destroys.get());
        oldScene.close();
        assertEquals(1, destroys.get());
    }

    @Test
    void worldExitKeepsPrewarmedPipelineAvailableForReentry() {
        AtomicInteger destroys = new AtomicInteger();
        var owner = new SharedResourceLifetime(destroys::incrementAndGet);
        owner.retain().close();
        assertEquals(0, destroys.get());
        var reenteredScene = owner.retain();
        reenteredScene.close();
        owner.close();
        assertEquals(1, destroys.get());
    }

    @Test
    void failedBindingReleasesItsLeaseWithoutRetiringOtherBindings() {
        AtomicInteger destroys = new AtomicInteger();
        var owner = new SharedResourceLifetime(destroys::incrementAndGet);
        var active = owner.retain();
        var failed = owner.retain();
        failed.close();
        owner.close();
        assertEquals(0, destroys.get());
        active.close();
        assertEquals(1, destroys.get());
    }

    @Test
    void unboundPipelineClosesExactlyOnce() {
        AtomicInteger destroys = new AtomicInteger();
        var owner = new SharedResourceLifetime(destroys::incrementAndGet);
        owner.close();
        owner.close();
        assertEquals(1, destroys.get());
        assertThrows(IllegalStateException.class, owner::retain);
    }

    @Test
    @Timeout(5)
    void concurrentShutdownAndDuplicateCompletionDestroyExactlyOnce() throws Exception {
        AtomicInteger destroys = new AtomicInteger();
        var owner = new SharedResourceLifetime(destroys::incrementAndGet);
        var lease = owner.retain();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(3)) {
            var shutdown = workers.submit(() -> { start.await(); owner.close(); return null; });
            var completion = workers.submit(() -> { start.await(); lease.close(); return null; });
            var duplicate = workers.submit(() -> { start.await(); lease.close(); return null; });
            start.countDown();
            shutdown.get();
            completion.get();
            duplicate.get();
        }
        assertEquals(1, destroys.get());
    }
}
