package dev.totem.lumen.integration;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import dev.totem.lumen.mixin.LumiaPipelineCacheAccessor;
import net.minecraft.client.renderer.RenderPipelines;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Runs the preparation lifecycle against a native cache and controllable device futures. */
final class LumiaPreparationVerifier {
    private LumiaPreparationVerifier() {}

    static void verify() throws Exception {
        var requests = new ArrayList<CompletableFuture<CompiledRenderPipeline.Pending>>();
        var field = RenderSystem.class.getDeclaredField("DEVICE");
        field.setAccessible(true);
        var previous = field.get(null);
        field.set(null, Proxy.newProxyInstance(GpuDevice.class.getClassLoader(), new Class<?>[]{GpuDevice.class},
                (proxy, method, arguments) -> {
                    if (!method.getName().equals("compilePipeline")) throw new AssertionError(method);
                    var future = new CompletableFuture<CompiledRenderPipeline.Pending>();
                    requests.add(future);
                    return future;
                }));
        try {
            var cache = new TestCache();
            LumiaPreparation.replaceCache(cache);
            LumiaPreparation.tick(false, () -> 0);
            if (!requests.isEmpty()) throw new AssertionError("Beta disabled submitted pipeline work");
            tick();
            tick();
            if (requests.size() != 1) throw new AssertionError("A pending compile did not bound concurrency");
            var stale = new Result();
            LumiaPreparation.invalidate();
            requests.getFirst().complete(() -> stale);
            tick();
            if (!stale.closed || requests.size() != 2 || cache.totemLumen$compiled().size() != 1)
                throw new AssertionError("A stale settings completion entered the cache");
            var installed = new Result();
            requests.getLast().complete(() -> installed);
            tick();
            require(LumiaPreparation.Status.READY);
            var variant = LumiaPipelines.drawVariant(RenderPipelines.SOLID_TERRAIN, 0);
            if (cache.get(variant) != installed) throw new AssertionError("Native draws cannot reuse the finished compile");
            LumiaPreparation.invalidate();
            tick();
            require(LumiaPreparation.Status.READY);
            if (requests.size() != 2) throw new AssertionError("An existing compiled pipeline was submitted again");

            var replacement = new TestCache();
            LumiaPreparation.replaceCache(replacement);
            require(LumiaPreparation.Status.IDLE);
            tick();
            requests.getLast().complete(CompiledRenderPipeline.Pending.NULL);
            tick();
            require(LumiaPreparation.Status.FAILED);
            tick();
            if (requests.size() != 3) throw new AssertionError("Failed batch resubmitted every tick");

            LumiaPreparation.invalidate();
            tick();
            var retired = new Result();
            requests.getLast().complete(() -> retired);
            LumiaPreparation.replaceCache(null);
            if (!retired.closed || replacement.totemLumen$compiled().size() != 1)
                throw new AssertionError("Retiring cache leaked an outstanding compile");
            require(LumiaPreparation.Status.IDLE);
        } finally {
            // Also allow a failing assertion to unwind without leaving a test future pending at shutdown.
            for (var future : requests) future.complete(CompiledRenderPipeline.Pending.NULL);
            LumiaPreparation.replaceCache(null);
            field.set(null, previous);
        }
    }

    private static void tick() {
        LumiaPreparation.tick(true, () -> 0);
    }

    private static void require(LumiaPreparation.Status expected) {
        if (LumiaPreparation.status() != expected)
            throw new AssertionError("Expected " + expected + ", got " + LumiaPreparation.status());
    }

    private static final class Result implements CompiledRenderPipeline {
        private boolean closed;

        public void close() { closed = true; }
        public boolean isClosed() { return closed; }
    }

    private static final class TestCache extends PipelineCache implements LumiaPipelineCacheAccessor {
        private final Map<RenderPipeline, CompiledRenderPipeline> compiled;

        @SuppressWarnings("unchecked")
        TestCache() throws ReflectiveOperationException {
            super(null, null);
            var cacheField = PipelineCache.class.getDeclaredField("cache");
            cacheField.setAccessible(true);
            compiled = (Map<RenderPipeline, CompiledRenderPipeline>) cacheField.get(this);
            insert(RenderPipelines.SOLID_TERRAIN, new Result());
        }

        public ShaderSource totemLumen$shaderSource() { return null; }
        public Map<RenderPipeline, CompiledRenderPipeline> totemLumen$compiled() { return compiled; }
    }
}
