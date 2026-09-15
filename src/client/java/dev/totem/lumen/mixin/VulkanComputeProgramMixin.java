package dev.totem.lumen.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.totem.lumen.vulkan.P16MultipassReflection;
import dev.totem.lumen.vulkan.VulkanComputeProgram;
import dev.totem.lumen.vulkan.resource.VulkanOwnedBuffer;
import org.lwjgl.util.shaderc.Shaderc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Starts the independent P16 pipeline and keeps its split shader on the proven O0 shaderc path. */
@Mixin(value = VulkanComputeProgram.class, remap = false)
public abstract class VulkanComputeProgramMixin {
    private static final String MAIN_GI_SHADER = "totem_lumen_p12_one_bounce_gi.comp";
    private static final String P16_WORKER = "TotemLumen-P16Pipeline";

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

    @Redirect(
            method = "compileShaderBytes",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/util/shaderc/Shaderc;shaderc_compile_options_set_optimization_level(JI)V"
            )
    )
    private static void totemLumen$selectOptimization(long options, int requestedLevel) {
        int effectiveLevel = Thread.currentThread().getName().equals(P16_WORKER)
                ? Shaderc.shaderc_optimization_level_zero
                : requestedLevel;
        Shaderc.shaderc_compile_options_set_optimization_level(options, effectiveLevel);
    }
}
