package dev.totem.lumen.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.totem.lumen.integration.LumiaPipelines;
import dev.totem.lumen.integration.LumiaScene;
import dev.totem.lumen.render.RendererSettings;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(ChunkSectionsToRender.class)
abstract class LumiaTerrainMixin {
    @ModifyArgs(method = "renderLayers", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;render(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;Lcom/mojang/renderpearl/api/pipeline/IndexType;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V"))
    private void totemLumen$shadeTerrain(Args args) {
        if (!RendererSettings.lumiaEnabled() || !LumiaScene.ready()) return;
        ChunkSectionLayer layer = args.get(0);
        RenderPipeline separate = args.get(4);
        RenderPipeline multi = args.get(5);
        if (separate == null) separate = layer.pipeline(false);
        if (multi == null) multi = layer.pipeline(true);
        var shaded = LumiaPipelines.terrainVariant(separate, layer.translucent());
        if (shaded == separate) return;
        args.set(4, shaded);
        args.set(5, LumiaPipelines.terrainVariant(multi, layer.translucent()));
        RenderPass pass = args.get(1);
        LumiaScene.bind(pass);
    }
}
