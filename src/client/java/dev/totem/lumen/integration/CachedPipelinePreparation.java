package dev.totem.lumen.integration;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.mixin.LumiaPipelineCacheAccessor;
import dev.totem.lumen.render.ProfilePreparation.Status;
import net.minecraft.util.Util;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Client-thread state, with one native asynchronous compile borrowing the live cache's shader sources. */
public final class CachedPipelinePreparation {
    private final String name;

    private volatile Status status = Status.IDLE;
    private volatile PipelineCache cache;
    private final ArrayDeque<RenderPipeline> remaining = new ArrayDeque<>();
    private long generation;
    private int variantCount;
    private Compile active;

    private record Compile(long generation, RenderPipeline pipeline,
                           CompletableFuture<CompiledRenderPipeline.Pending> future) {}

    public CachedPipelinePreparation(String name) { this.name = name; }

    public Status status() {
        return currentDevice(cache) ? status : Status.IDLE;
    }

    public void invalidate() {
        generation++;
        remaining.clear();
        status = Status.IDLE;
    }

    public void replaceCache(PipelineCache next) {
        if (cache == next) return;
        invalidate();
        // Reload/shutdown is about to free the native shaderc include pointers and possibly the device.
        // Only this lifetime boundary waits. Ordinary ticks and settings changes never wait for a compile.
        if (active != null) finish();
        cache = next;
    }

    private static boolean currentDevice(PipelineCache cache) {
        if (cache == null) return false;
        var owner = ((LumiaPipelineCacheAccessor) cache).totemLumen$device();
        return owner != null && owner == RenderSystem.tryGetDevice();
    }

    public void tick(boolean enabled, Function<Set<RenderPipeline>, Collection<RenderPipeline>> variants) {
        if (active != null) {
            if (!active.future().isDone()) return;
            finish();
        }
        if (!enabled || !currentDevice(cache) || status == Status.READY || status == Status.FAILED)
            return;

        var access = (LumiaPipelineCacheAccessor) cache;
        try {
            if (status == Status.IDLE) {
                remaining.addAll(variants.apply(access.totemLumen$compiled().keySet()));
                if (remaining.isEmpty()) throw new IllegalStateException("No " + name + " pipeline variants for the current cache");
                variantCount = remaining.size();
                status = Status.PREPARING;
                TotemLumenClient.LOGGER.info("Preparing {} {} pipelines in the background", variantCount, name);
            }
            while (!remaining.isEmpty()) {
                var pipeline = remaining.removeFirst();
                if (access.totemLumen$compiled().get(pipeline) != null) continue;
                var future = access.totemLumen$device().compilePipeline(pipeline, access.totemLumen$shaderSource(),
                        Util.backgroundExecutor());
                active = new Compile(generation, pipeline, future);
                return;
            }
            status = Status.READY;
            TotemLumenClient.LOGGER.info("{} pipelines ready ({})", name, variantCount);
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    private void finish() {
        var job = active;
        active = null;
        try {
            var compiled = job.future().join().finishCompile();
            if (compiled == null) throw new IllegalStateException("Could not compile " + job.pipeline().getLocation());
            if (job.generation() == generation && currentDevice(cache)
                    && ((LumiaPipelineCacheAccessor) cache).totemLumen$compiled().get(job.pipeline()) == null) {
                cache.insert(job.pipeline(), compiled);
            } else {
                compiled.close();
            }
        } catch (RuntimeException e) {
            if (job.generation() == generation) fail(e);
        }
    }

    private void fail(RuntimeException failure) {
        remaining.clear();
        status = Status.FAILED;
        TotemLumenClient.LOGGER.error("{} pipeline preparation failed", name, failure);
    }
}
