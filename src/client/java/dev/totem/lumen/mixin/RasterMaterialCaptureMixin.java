package dev.totem.lumen.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.totem.lumen.integration.RasterMaterialCapture;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retains only this frame's native opaque draw list, never a mutable world snapshot. */
@Mixin(ChunkSectionsToRender.class)
public abstract class RasterMaterialCaptureMixin {
    @Inject(method = "renderGroup", at = @At("HEAD"))
    private void totemLumen$rememberOpaque(ChunkSectionLayerGroup group, RenderPass pass,
            GpuSampler sampler, GpuTextureView atlas, boolean wireframe, CallbackInfo ci) {
        if (group == ChunkSectionLayerGroup.OPAQUE && !wireframe) {
            RasterMaterialCapture.remember((ChunkSectionsToRender) (Object) this, sampler, atlas);
        }
    }
}
