package dev.totem.lumen.vulkan;

import dev.totem.lumen.vulkan.resource.VulkanWorkerLifetime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/** Isolated native-work gate probe. No Minecraft device, queue submission or rendering. */
public final class VulkanWorkerShutdownResearch {
    private VulkanWorkerShutdownResearch() {}

    static <T> T run(Supplier<T> operation, boolean interruptShutdown, Map<String, Object> report) {
        return run(operation, interruptShutdown, report, false);
    }

    private static <T> T run(Supplier<T> operation, boolean interruptShutdown,
                             Map<String, Object> report, boolean bypassGateForNegativeControl) {
        var lifetime = new VulkanWorkerLifetime();
        var lease = lifetime.tryAcquire(); // Reserve before thread start, just like production.
        var release = new CountDownLatch(1);
        var value = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        var workFinished = new AtomicBoolean();
        Thread caller = Thread.currentThread();
        long[] times = new long[3]; // Published through the lifetime monitor before the lease closes.
        Thread worker = new Thread(() -> {
            try {
                release.await();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                // The caller has no other untimed wait in this scope. Do not label fast native
                // creation as an overlap unless the shutdown gate was actually observed waiting.
                while (caller.getState() != Thread.State.WAITING) {
                    if (System.nanoTime() >= deadline)
                        throw new IllegalStateException("Shutdown gate was not observed waiting");
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                times[0] = System.nanoTime();
                value.set(operation.get());
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                workFinished.set(true);
                lease.close();
            }
        }, "TotemLumen-WorkerShutdownResearch");
        // Native calls cannot safely be cancelled. The existing process supervisor owns timeout.
        boolean started = false;
        try {
            worker.start();
            started = true;
            int active = lifetime.stopAccepting();
            report.put("activeWorkersAtShutdown", active);
            if (active != 1) throw new IllegalStateException("Expected one reserved native worker");
            var rejected = lifetime.tryAcquire();
            if (rejected != null) {
                rejected.close();
                throw new IllegalStateException("Shutdown admitted new native work");
            }
            report.put("newWorkRejected", true);
            if (interruptShutdown) caller.interrupt();
            times[1] = System.nanoTime();
        } finally {
            if (!started) lease.close();
            lifetime.stopAccepting();
            release.countDown();
            if (!bypassGateForNegativeControl) lifetime.awaitCompletion();
            // Sample BEFORE defensive cleanup/join: joining alone must not conceal a broken gate.
            report.put("workFinishedAtGateReturn", workFinished.get());
            lifetime.awaitCompletion(); // Keep even a deliberately broken test safe.
            boolean interrupted = Thread.interrupted();
            while (started && worker.isAlive()) {
                try {
                    worker.join();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) caller.interrupt();
            times[2] = System.nanoTime();
            report.put("shutdownGateWaitObserved", times[0] != 0);
            report.put("shutdownWaitNs", times[1] == 0 ? 0 : times[2] - times[1]);
            report.put("interruptRequested", interruptShutdown);
            report.put("interruptRestored", caller.isInterrupted());
            report.put("workerTerminatedBeforeCleanup", !worker.isAlive());
        }
        if (failure.get() != null) throw new IllegalStateException("Worker probe failed", failure.get());
        if (!Boolean.TRUE.equals(report.get("workFinishedAtGateReturn")))
            throw new IllegalStateException("Shutdown gate returned before native work finished");
        if (times[0] == 0) throw new IllegalStateException("Native work did not overlap shutdown wait");
        return value.get();
    }

    /** Deterministic gate/probe checks, including exceptional work; no native library required. */
    public static void main(String[] args) {
        int checks = 0;
        for (boolean interrupt : new boolean[]{false, true}) {
            for (boolean fail : new boolean[]{false, true}) {
                Map<String, Object> report = new LinkedHashMap<>();
                var expectedFailure = new IllegalArgumentException("injected worker failure");
                try {
                    int result = run(() -> {
                        if (fail) throw expectedFailure;
                        return 42;
                    }, interrupt, report);
                    if (fail || result != 42) throw new AssertionError("Unexpected worker outcome");
                } catch (IllegalStateException error) {
                    if (!fail || error.getCause() != expectedFailure) throw error;
                } finally {
                    boolean restored = Thread.interrupted();
                    if (restored != interrupt) throw new AssertionError("Interrupt flag not preserved");
                }
                for (String field : new String[]{"newWorkRejected", "shutdownGateWaitObserved",
                        "workerTerminatedBeforeCleanup", "workFinishedAtGateReturn"}) {
                    if (!Boolean.TRUE.equals(report.get(field))) throw new AssertionError(field);
                }
                checks++;
            }
        }
        Map<String, Object> negative = new LinkedHashMap<>();
        try {
            run(() -> 42, false, negative, true);
            throw new AssertionError("Bypassed shutdown gate was accepted");
        } catch (IllegalStateException expected) {
            if (!Boolean.FALSE.equals(negative.get("workFinishedAtGateReturn"))) throw expected;
        }
        System.out.println("Worker shutdown research verification PASS: " + checks
                + " cases (success/failure, ordinary/interrupted), bypass negative control rejected; no GPU acceptance claimed");
    }
}
