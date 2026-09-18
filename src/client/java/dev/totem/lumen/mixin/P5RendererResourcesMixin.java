package dev.totem.lumen.mixin;

import dev.totem.lumen.gpu.GpuDynamicEntityScene;
import dev.totem.lumen.gpu.GpuFluidScene;
import dev.totem.lumen.vulkan.P14ModelMeshGpuLayout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Extends P5 upload/storage allocation with fixed-capacity P14, P17 and P14E scene tails. */
@Mixin(targets = "dev.totem.lumen.vulkan.P5StableLookupRenderer$Resources", remap = false)
public abstract class P5RendererResourcesMixin {
    @Redirect(
            method = "create",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Math;addExact(II)I",
                    ordinal = 2
            )
    )
    private static int totemLumen$appendGeometryStorage(int pixelBaseWord, int pixelCount) {
        int existingWords = Math.addExact(pixelBaseWord, pixelCount);
        int withP14 = Math.addExact(existingWords, P14ModelMeshGpuLayout.MAX_STORAGE_WORDS);
        int withP17 = Math.addExact(withP14, GpuDynamicEntityScene.MAX_STORAGE_WORDS);
        return Math.addExact(withP17, GpuFluidScene.MAX_STORAGE_WORDS);
    }
}
