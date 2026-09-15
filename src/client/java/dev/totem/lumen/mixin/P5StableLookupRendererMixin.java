package dev.totem.lumen.mixin;

import dev.totem.lumen.vulkan.P16MultipassReflection;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Inserts the optional P16 reflection pass between the base compute pass and its copy-to-image barrier. */
@Mixin(targets = "dev.totem.lumen.vulkan.P5StableLookupRenderer", remap = false)
public abstract class P5StableLookupRendererMixin {
    @Redirect(
            method = "recordCommands",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VK10;vkCmdDispatch(Lorg/lwjgl/vulkan/VkCommandBuffer;III)V"
            )
    )
    private static void totemLumen$dispatchBaseThenReflection(
            VkCommandBuffer commandBuffer,
            int groupCountX,
            int groupCountY,
            int groupCountZ
    ) {
        VK10.vkCmdDispatch(commandBuffer, groupCountX, groupCountY, groupCountZ);
        P16MultipassReflection.recordAfterBaseDispatch(
                commandBuffer,
                groupCountX,
                groupCountY,
                groupCountZ
        );
    }

    @Inject(method = "shutdown", at = @At("HEAD"))
    private static void totemLumen$shutdownReflection(CallbackInfo ci) {
        P16MultipassReflection.shutdown();
    }
}
