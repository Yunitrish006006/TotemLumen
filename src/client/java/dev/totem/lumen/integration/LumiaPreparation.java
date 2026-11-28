package dev.totem.lumen.integration;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.mixin.LumiaPipelineCacheAccessor;
import dev.totem.lumen.render.RendererSettings;
import net.minecraft.util.Util;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;

/** Client-thread state, with one native asynchronous compile borrowing the live cache's shader sources. */
public final class LumiaPreparation {
    public enum Status { IDLE, PREPARING, READY, FAILED }

    private static volatile Status status = Status.IDLE;
    private static PipelineCache cache;
    private static final ArrayDeque<RenderPipeline> remaining = new ArrayDeque<>();
    private static long generation;
    private static int variantCount;
    private static Compile active;

    private record Compile(long generation, RenderPipeline pipeline,
                           CompletableFuture<CompiledRenderPipeline.Pending> future) {}

    private LumiaPreparation() {}

    public static Status status() {
        return status;
    }

    public static void invalidate() {
        generation++;
        remaining.clear();
        status = Status.IDLE;
    }

    public static void replaceCache(PipelineCache next) {
        if (cache == next) return;
        invalidate();
        // Reload/shutdown is about to free the native shaderc include pointers and possibly the device.
        // Only this lifetime boundary waits. Ordinary ticks and settings changes never wait for a compile.
        if (active != null) finish();
        cache = next;
    }

    public static void tick() {
        tick(RendererSettings.betaRendering(), LumiaPipelines::currentQualityFlags);
    }

    static void tick(boolean enabled, IntSupplier qualityFlags) {
        if (active != null) {
            if (!active.future().isDone()) return;
            finish();
        }
        if (!enabled || cache == null || status == Status.READY || status == Status.FAILED)
            return;

        var access = (LumiaPipelineCacheAccessor) cache;
        try {
            if (status == Status.IDLE) {
                remaining.addAll(LumiaPipelines.preparationVariants(access.totemLumen$compiled().keySet(),
                        qualityFlags.getAsInt()));
                if (remaining.isEmpty()) throw new IllegalStateException("No Lumia pipeline variants for the current cache");
                variantCount = remaining.size();
                status = Status.PREPARING;
                TotemLumenClient.LOGGER.info("Preparing {} Lumia pipelines in the background", variantCount);
            }
            while (!remaining.isEmpty()) {
                var pipeline = remaining.removeFirst();
                if (access.totemLumen$compiled().get(pipeline) != null) continue;
                var future = RenderSystem.getDevice().compilePipeline(pipeline, access.totemLumen$shaderSource(),
                        Util.backgroundExecutor());
                active = new Compile(generation, pipeline, future);
                return;
            }
            status = Status.READY;
            TotemLumenClient.LOGGER.info("Lumia pipelines ready ({})", variantCount);
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    private static void finish() {
        var job = active;
        active = null;
        try {
            var compiled = job.future().join().finishCompile();
            if (compiled == null) throw new IllegalStateException("Could not compile " + job.pipeline().getLocation());
            if (job.generation() == generation
                    && ((LumiaPipelineCacheAccessor) cache).totemLumen$compiled().get(job.pipeline()) == null) {
                cache.insert(job.pipeline(), compiled);
            } else {
                compiled.close();
            }
        } catch (RuntimeException e) {
            if (job.generation() == generation) fail(e);
        }
    }

    private static void fail(RuntimeException failure) {
        remaining.clear();
        status = Status.FAILED;
        TotemLumenClient.LOGGER.error("Lumia pipeline preparation failed", failure);
    }
}
