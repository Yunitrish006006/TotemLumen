package dev.totem.lumen.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.totem.lumen.vulkan.P16MultipassReflection;
import dev.totem.lumen.vulkan.VulkanComputeProgram;
import dev.totem.lumen.vulkan.resource.VulkanOwnedBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Starts the independent P16 pipeline as soon as the base renderer binds a scene SSBO. */
@Mixin(value = VulkanComputeProgram.class, remap = false)
public abstract class VulkanComputeProgramMixin {
    private static final String MAIN_GI_SHADER = "totem_lumen_p12_one_bounce_gi.comp";

    @Inject(method = "create", at = @At("RETURN"))
    private static void totemLumen$attachP16(
            VulkanDevice device,
            String name,
            String glsl,
            VulkanOwnedBuffer storage,
            CallbackInfoReturnable<VulkanComputeProgram> cir
    ) {
        if (MAIN_GI_SHADER.equals(name) && cir.getReturnValue() != null) {
            P16MultipassReflection.attach(device, storage);
        }
    }
}
