package dev.totem.lumen.integration;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** One render-thread-owned pipeline for the current client resource generation. */
final class RasterPipelineJob implements AutoCloseable {
    private final RenderPipeline template;
    private CompletableFuture<CompiledRenderPipeline.Pending> pending;
    private CompiledRenderPipeline compiled;

    RasterPipelineJob(RenderPipeline template) {
        this.template = template;
    }

    CompiledRenderPipeline poll(GpuDevice gpu) {
        if (compiled != null) return compiled;
        if (pending == null) {
            var resources = Minecraft.getInstance().getResourceManager();
            Map<Identifier, String> shaders = new HashMap<>();
            template.getShaders().forEach((type, id) -> {
                var file = type.idConverter().idToFile(id);
                try {
                    shaders.put(file, resources.getResourceOrThrow(file).readAllAsString());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            var includes = ShaderManager.listAllIncludes(resources);
            var source = new ShaderSource() {
                public String getShader(Identifier id, ShaderType type) { return shaders.get(type.idConverter().idToFile(id)); }
                public CachedIncludeSource getInclude(Identifier id) { return includes.get(id); }
                public void close() { includes.values().forEach(CachedIncludeSource::close); }
            };
            try {
                pending = gpu.compilePipeline(template, source, Util.backgroundExecutor())
                        .whenComplete((result, failure) -> source.close());
            } catch (RuntimeException failure) {
                source.close();
                throw failure;
            }
        }
        if (pending.isDone()) finish();
        return compiled;
    }

    private void finish() {
        var job = pending;
        pending = null;
        compiled = job.join().finishCompile();
        if (compiled == null) throw new IllegalStateException("Unable to compile " + template.getLocation());
    }

    public void close() {
        try {
            // Only reload/shutdown drains an unfinished job. Normal frames only poll.
            if (pending != null) finish();
        } finally {
            if (compiled != null) compiled.close();
            compiled = null;
        }
    }
}
