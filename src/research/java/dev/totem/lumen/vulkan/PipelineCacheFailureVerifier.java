package dev.totem.lumen.vulkan;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercises production bookkeeping with injected outcomes, without creating any native handles. */
public final class PipelineCacheFailureVerifier {
    public static void main(String[] args) throws Exception {
        try {
            for (Throwable expected : new Throwable[]{new IllegalStateException("compiler failure"),
                    new AssertionError("compiler error")}) {
                reset(1, true);
                try {
                    VulkanPipelineCacheStore.runCreation(true, "injected", () -> {
                        if (expected instanceof Error error) throw error;
                        throw (RuntimeException) expected;
                    });
                    throw new AssertionError("Failure was swallowed");
                } catch (RuntimeException | Error actual) {
                    require(actual == expected, "Original failure identity changed");
                }
                require(count() == 0 && !flag("destroyWhenIdle"), "Exceptional reservation leaked");
                require(!flag("dirty"), "Failure was marked successful");
            }

            reset(1, false);
            var failed = result(-3);
            require(VulkanPipelineCacheStore.runCreation(true, "injected", () -> failed) == failed,
                    "Vulkan error result changed");
            require(count() == 0 && !flag("dirty"), "Error-code reservation leaked");

            reset(2, false);
            var success = result(0);
            require(VulkanPipelineCacheStore.runCreation(true, "injected", () -> success) == success,
                    "Success result changed");
            require(count() == 1 && flag("dirty"), "Success did not retain dirty state for remaining worker");

            reset(2, true);
            VulkanPipelineCacheStore.runCreation(false, "uncached", () -> failed);
            require(count() == 2 && flag("destroyWhenIdle"), "Uncached work changed cache ownership");
            VulkanPipelineCacheStore.runCreation(true, "first", () -> failed);
            require(count() == 1 && flag("destroyWhenIdle"), "Closed while another worker remained");
            VulkanPipelineCacheStore.runCreation(true, "last", () -> failed);
            require(count() == 0 && !flag("destroyWhenIdle"), "Last completion did not finish deferred close");

            verifyNotification();
            System.out.println("Pipeline cache failure verification PASS: RuntimeException, Error, Vulkan error, "
                    + "success, uncached ownership, overlapping completion, waiter notification; no native destruction claim");
        } finally {
            reset(0, false);
        }
    }

    private static void verifyNotification() throws Exception {
        reset(1, false);
        Object lock = field("LOCK").get(null);
        var waiting = new CountDownLatch(1);
        var notified = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            synchronized (lock) {
                waiting.countDown();
                try {
                    while (count() != 0) lock.wait();
                    notified.set(true);
                } catch (Exception failure) {
                    // The final assertion fails if bookkeeping never wakes this waiter.
                }
            }
        }, "PipelineCacheFailureVerifier-waiter");
        waiter.start();
        try {
            require(waiting.await(5, TimeUnit.SECONDS), "Waiter did not start");
            VulkanPipelineCacheStore.runCreation(true, "notify", () -> result(-3));
            waiter.join(5_000);
            require(notified.get() && !waiter.isAlive(), "Completion failed to notify waiter");
        } finally {
            waiter.interrupt();
            waiter.join(5_000);
        }
    }

    private static VulkanPipelineCreationDiagnostics.Result result(int code) {
        return new VulkanPipelineCreationDiagnostics.Result(code, 0, false, false, false, 0);
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = VulkanPipelineCacheStore.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void reset(int count, boolean closing) throws ReflectiveOperationException {
        // Zero native handle deliberately avoids disk/GPU work; this tests bookkeeping, not vkDestroy.
        field("activePipelineCache").setLong(null, 0);
        field("activeDevice").set(null, null);
        field("activeCacheFile").set(null, null);
        field("activeCreates").setInt(null, count);
        field("dirty").setBoolean(null, false);
        field("destroyWhenIdle").setBoolean(null, closing);
    }

    private static int count() throws ReflectiveOperationException { return field("activeCreates").getInt(null); }
    private static boolean flag(String name) throws ReflectiveOperationException { return field(name).getBoolean(null); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
