package dev.totem.lumen.mixin;

import dev.totem.lumen.vulkan.P14ModelMeshGpuLayout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Extends the P5 upload/storage allocation with a fixed-capacity P14C model-mesh tail. */
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
    private static int totemLumen$appendModelMeshStorage(int pixelBaseWord, int pixelCount) {
        int existingWords = Math.addExact(pixelBaseWord, pixelCount);
        return Math.addExact(existingWords, P14ModelMeshGpuLayout.MAX_STORAGE_WORDS);
    }
}
