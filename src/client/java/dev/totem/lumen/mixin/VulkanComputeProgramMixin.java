package dev.totem.lumen.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.totem.lumen.vulkan.P16MultipassReflection;
import dev.totem.lumen.vulkan.P17EnhancedBasePipeline;
import dev.totem.lumen.vulkan.ShadercNativeHeap;
import dev.totem.lumen.vulkan.VulkanComputeProgram;
import dev.totem.lumen.vulkan.resource.VulkanOwnedBuffer;
import org.lwjgl.util.shaderc.Shaderc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Starts optional split pipelines, keeps their large GLSL on the O0 shaderc path, and keeps large
 * transformed source strings off LWJGL's bounded MemoryStack.
 *
 * <p>P17 is intentionally not injected into transformMainGiShader(): the P12-P15 main pipeline is
 * the renderer readiness gate. P17 compiles independently and is selected only after its complete
 * VulkanComputeProgram is ready.</p>
 */
@Mixin(value = VulkanComputeProgram.class, remap = false)
public abstract class VulkanComputeProgramMixin {
    private static final String MAIN_GI_SHADER = "totem_lumen_p12_one_bounce_gi.comp";
    private static final String P16_WORKER = "TotemLumen-P16Pipeline";
    private static final String P17_WORKER = "TotemLumen-P17Pipeline";

    @Inject(method = "create", at = @At("RETURN"))
    private static void totemLumen$attachOptionalPipelines(
            VulkanDevice device,
            String name,
            String glsl,
            VulkanOwnedBuffer storage,
            CallbackInfoReturnable<VulkanComputeProgram> cir
    ) {
        if (MAIN_GI_SHADER.equals(name) && cir.getReturnValue() != null) {
            P17EnhancedBasePipeline.attach(device, storage);
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
        String threadName = Thread.currentThread().getName();
        int effectiveLevel = threadName.equals(P16_WORKER) || threadName.equals(P17_WORKER)
                ? Shaderc.shaderc_optimization_level_zero
                : requestedLevel;
        Shaderc.shaderc_compile_options_set_optimization_level(options, effectiveLevel);
    }

    @Redirect(
            method = "compileShaderBytes",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/util/shaderc/Shaderc;shaderc_compile_into_spv(JLjava/lang/CharSequence;ILjava/lang/CharSequence;Ljava/lang/CharSequence;J)J"
            )
    )
    private static long totemLumen$compileLargeSourceOffStack(
            long compiler,
            CharSequence sourceText,
            int shaderKind,
            CharSequence inputFileName,
            CharSequence entryPointName,
            long options
    ) {
        return ShadercNativeHeap.compileIntoSpv(
                compiler,
                sourceText,
                shaderKind,
                inputFileName,
                entryPointName,
                options
        );
    }
}
