package dev.totem.lumen.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.totem.lumen.vulkan.P17EnhancedBasePipeline;
import dev.totem.lumen.vulkan.P5StableLookupRenderer;
import dev.totem.lumen.vulkan.VulkanComputeProgram;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Selects the optional P17-enhanced program once per command recording.
 *
 * <p>The snapshot is held in a render-thread ThreadLocal so the pipeline, layout and descriptor set
 * field reads within one recordCommands invocation always come from the same VulkanComputeProgram,
 * even if the background P17 worker becomes ready during that method.</p>
 */
@Mixin(value = P5StableLookupRenderer.class, remap = false)
public abstract class P5StableLookupRendererP17Mixin {
    @Inject(method = "recordCommands", at = @At("HEAD"))
    private static void totemLumen$beginP17Dispatch(CallbackInfo ci) {
        P17EnhancedBasePipeline.beginDispatch();
    }

    @ModifyExpressionValue(
            method = "recordCommands",
            at = @At(
                    value = "FIELD",
                    target = "Ldev/totem/lumen/vulkan/P5StableLookupRenderer$Resources;program:Ldev/totem/lumen/vulkan/VulkanComputeProgram;",
                    opcode = Opcodes.GETFIELD
            )
    )
    private static VulkanComputeProgram totemLumen$selectP17Program(VulkanComputeProgram original) {
        return P17EnhancedBasePipeline.selectForCurrentDispatch(original);
    }

    @Inject(method = "recordCommands", at = @At("RETURN"))
    private static void totemLumen$endP17Dispatch(CallbackInfo ci) {
        P17EnhancedBasePipeline.endDispatch();
    }
}
