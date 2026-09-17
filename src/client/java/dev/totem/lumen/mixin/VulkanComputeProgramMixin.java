package dev.totem.lumen.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.totem.lumen.vulkan.P12FullBasePipeline;
import dev.totem.lumen.vulkan.P5BootstrapShader;
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
 * Keeps renderer readiness independent from expensive MoltenVK pipelines.
 *
 * <p>The early TotemLumen-ShaderPrewarm thread receives a tiny bootstrap shader. Once that program
 * is bound to the real scene buffer, P12FullBasePipeline starts the full P12-P15 pipeline on a
 * daemon worker. P17 and P16 start only after the full base completes. All large optional shaders
 * stay on shaderc O0 and all shaderc source buffers remain off LWJGL's bounded MemoryStack.</p>
 */
@Mixin(value = VulkanComputeProgram.class, remap = false)
public abstract class VulkanComputeProgramMixin {
    private static final String MAIN_GI_SHADER = "totem_lumen_p12_one_bounce_gi.comp";
    private static final String BOOTSTRAP_WORKER = "TotemLumen-ShaderPrewarm";
    private static final String P12_FULL_WORKER = "TotemLumen-P12FullPipeline";
    private static final String P16_WORKER = "TotemLumen-P16Pipeline";
    private static final String P17_WORKER = "TotemLumen-P17Pipeline";

    @Inject(method = "transformMainGiShader", at = @At("HEAD"), cancellable = true)
    private static void totemLumen$useBootstrapForReadiness(
            String source,
            CallbackInfoReturnable<String> cir
    ) {
        if (Thread.currentThread().getName().equals(BOOTSTRAP_WORKER)) {
            cir.setReturnValue(P5BootstrapShader.source());
        }
    }

    @Inject(method = "create", at = @At("RETURN"))
    private static void totemLumen$attachFullBasePipeline(
            VulkanDevice device,
            String name,
            String glsl,
            VulkanOwnedBuffer storage,
            CallbackInfoReturnable<VulkanComputeProgram> cir
    ) {
        if (MAIN_GI_SHADER.equals(name) && cir.getReturnValue() != null) {
            P12FullBasePipeline.attach(device, storage);
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
        int effectiveLevel = threadName.equals(P12_FULL_WORKER)
                || threadName.equals(P16_WORKER)
                || threadName.equals(P17_WORKER)
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
