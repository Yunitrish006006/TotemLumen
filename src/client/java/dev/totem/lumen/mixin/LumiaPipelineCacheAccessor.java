package dev.totem.lumen.mixin;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(PipelineCache.class)
public interface LumiaPipelineCacheAccessor {
    @Accessor("shaderSource")
    ShaderSource totemLumen$shaderSource();

    @Accessor("cache")
    Map<RenderPipeline, CompiledRenderPipeline> totemLumen$compiled();
}
