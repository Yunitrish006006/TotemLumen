package dev.totem.lumen.integration;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import dev.totem.lumen.mixin.LumiaPipelineCacheAccessor;
import net.minecraft.client.renderer.RenderPipelines;

import dev.totem.lumen.render.ProfilePreparation;

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
                    if (method.getName().equals("getDeviceInfo")) {
                        return new DeviceInfo("test", "test", "test", true, "Vulkan", 1f,
                                null, null, java.util.Set.of(), null, null);
                    }
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
            require(ProfilePreparation.Status.READY);
            var variant = LumiaPipelines.drawVariant(RenderPipelines.SOLID_TERRAIN, 0);
            if (cache.get(variant) != installed) throw new AssertionError("Native draws cannot reuse the finished compile");
            LumiaPreparation.invalidate();
            tick();
            require(ProfilePreparation.Status.READY);
            if (requests.size() != 2) throw new AssertionError("An existing compiled pipeline was submitted again");

            var replacement = new TestCache();
            LumiaPreparation.replaceCache(replacement);
            require(ProfilePreparation.Status.IDLE);
            tick();
            requests.getLast().complete(CompiledRenderPipeline.Pending.NULL);
            tick();
            require(ProfilePreparation.Status.FAILED);
            tick();
            if (requests.size() != 3) throw new AssertionError("Failed batch resubmitted every tick");

            LumiaPreparation.invalidate();
            tick();
            var retired = new Result();
            requests.getLast().complete(() -> retired);
            LumiaPreparation.replaceCache(null);
            if (!retired.closed || replacement.totemLumen$compiled().size() != 1)
                throw new AssertionError("Retiring cache leaked an outstanding compile");
            require(ProfilePreparation.Status.IDLE);

            requests.clear();
            RasterLightingRenderer.replaceCache(cache);
            RasterLightingRenderer.tickPreparation(false);
            if (!requests.isEmpty()) throw new AssertionError("Raster prepared while beta was disabled");
            // Preparation must finish with no level, command encoders, textures or render passes.
            int rasterVariants = 2;
            var materialVariants = new java.util.LinkedHashSet<RenderPipeline>();
            RasterMaterialCapture.addPreparationVariants(materialVariants);
            rasterVariants += materialVariants.size();
            for (int i = 0; i < rasterVariants; i++) {
                RasterLightingRenderer.tickPreparation(true);
                RasterLightingRenderer.tickPreparation(true);
                if (requests.size() != i + 1 || RasterLightingRenderer.preparationStatus() != ProfilePreparation.Status.PREPARING)
                    throw new AssertionError("Raster became ready early or exceeded its compile budget");
                requests.getLast().complete(Result::new);
            }
            RasterLightingRenderer.tickPreparation(true);
            if (RasterLightingRenderer.preparationStatus() != ProfilePreparation.Status.READY
                    || !cache.totemLumen$compiled().keySet().containsAll(materialVariants))
                throw new AssertionError("Raster did not install all draw pipelines");
            RasterLightingRenderer.invalidatePreparation();
            RasterLightingRenderer.tickPreparation(true);
            if (requests.size() != rasterVariants || RasterLightingRenderer.preparationStatus() != ProfilePreparation.Status.READY)
                throw new AssertionError("Raster failed to reuse its finished pipelines");
            var owner = field.get(null);
            field.set(null, null);
            RasterLightingRenderer.tickPreparation(true);
            if (RasterLightingRenderer.preparationStatus() != ProfilePreparation.Status.IDLE
                    || requests.size() != rasterVariants)
                throw new AssertionError("Raster retained readiness after its device retired");
            field.set(null, owner);
            RasterLightingRenderer.replaceCache(replacement);
            RasterLightingRenderer.tickPreparation(true);
            requests.getLast().complete(CompiledRenderPipeline.Pending.NULL);
            RasterLightingRenderer.tickPreparation(true);
            if (RasterLightingRenderer.preparationStatus() != ProfilePreparation.Status.FAILED)
                throw new AssertionError("Raster accepted a failed replacement pipeline");
        } finally {
            // Also allow a failing assertion to unwind without leaving a test future pending at shutdown.
            for (var future : requests) future.complete(CompiledRenderPipeline.Pending.NULL);
            LumiaPreparation.replaceCache(null);
            RasterLightingRenderer.replaceCache(null);
            field.set(null, previous);
        }
    }

    private static void tick() {
        LumiaPreparation.tick(true, () -> 0);
    }

    private static void require(ProfilePreparation.Status expected) {
        if (LumiaPreparation.status() != expected)
            throw new AssertionError("Expected " + expected + ", got " + LumiaPreparation.status());
    }

    private static final class Result implements CompiledRenderPipeline {
        private boolean closed;

        public void close() { closed = true; }
        public boolean isClosed() { return closed; }
    }

    private static final class TestCache extends PipelineCache implements LumiaPipelineCacheAccessor {
        private final GpuDevice owner = RenderSystem.getDevice();
        private final Map<RenderPipeline, CompiledRenderPipeline> compiled;

        @SuppressWarnings("unchecked")
        TestCache() throws ReflectiveOperationException {
            super(RenderSystem.getDevice(), null);
            var cacheField = PipelineCache.class.getDeclaredField("cache");
            cacheField.setAccessible(true);
            compiled = (Map<RenderPipeline, CompiledRenderPipeline>) cacheField.get(this);
            insert(RenderPipelines.SOLID_TERRAIN, new Result());
        }

        public GpuDevice totemLumen$device() { return owner; }
        public ShaderSource totemLumen$shaderSource() { return null; }
        public Map<RenderPipeline, CompiledRenderPipeline> totemLumen$compiled() { return compiled; }
    }
}
