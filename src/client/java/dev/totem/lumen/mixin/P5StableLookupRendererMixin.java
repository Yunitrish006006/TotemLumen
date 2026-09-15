package dev.totem.lumen.mixin;

import dev.totem.lumen.scene.SectionSnapshot;
import dev.totem.lumen.vulkan.P14ModelMeshGpuUploader;
import dev.totem.lumen.vulkan.P16MultipassReflection;
import dev.totem.lumen.vulkan.resource.VulkanOwnedBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Shared P14C/P14D scene-tail upload hooks plus the optional P16 reflection dispatch.
 *
 * <p>Static model descriptors are packed during ordinary full scene uploads. P14D mutable block-
 * entity mesh revisions can additionally repack/copy only the model tail on a camera-only frame,
 * avoiding a 64-section voxel repack for every animation pose.</p>
 */
@Mixin(targets = "dev.totem.lumen.vulkan.P5StableLookupRenderer", remap = false)
public abstract class P5StableLookupRendererMixin {
    private static final long CAMERA_UPLOAD_MAX_BYTES = 4096L;

    @Inject(method = "packLookupSectionsAndLights", at = @At("TAIL"))
    private static void totemLumen$packModelMeshes(
            ByteBuffer buffer,
            List<SectionSnapshot> sections,
            CallbackInfo ci
    ) {
        P14ModelMeshGpuUploader.pack(buffer);
    }

    @Redirect(
            method = "runOnRenderThread",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/totem/lumen/vulkan/resource/VulkanOwnedBuffer;flush(JJ)V"
            )
    )
    private static void totemLumen$flushStaticAndModelTail(
            VulkanOwnedBuffer upload,
            long offset,
            long length
    ) {
        boolean modelChanged = P14ModelMeshGpuUploader.packIfDirty(upload.mappedView());
        upload.flush(offset, length);
        if (length <= CAMERA_UPLOAD_MAX_BYTES && !modelChanged) return;

        long modelOffset = P14ModelMeshGpuUploader.lastBaseByteOffset();
        long modelBytes = P14ModelMeshGpuUploader.lastCopyBytes();
        if (modelOffset >= 0L && modelBytes > 0L) upload.flush(modelOffset, modelBytes);
    }

    @Redirect(
            method = "recordCommands",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VK10;vkCmdCopyBuffer(Lorg/lwjgl/vulkan/VkCommandBuffer;JJLorg/lwjgl/vulkan/VkBufferCopy$Buffer;)V"
            )
    )
    private static void totemLumen$copyStaticAndModelTail(
            VkCommandBuffer commandBuffer,
            long sourceBuffer,
            long destinationBuffer,
            VkBufferCopy.Buffer regions
    ) {
        VK10.vkCmdCopyBuffer(commandBuffer, sourceBuffer, destinationBuffer, regions);
        if (!P14ModelMeshGpuUploader.consumeCopyPending()) return;

        long modelOffset = P14ModelMeshGpuUploader.lastBaseByteOffset();
        long modelBytes = P14ModelMeshGpuUploader.lastCopyBytes();
        if (modelOffset < 0L || modelBytes <= 0L) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer modelCopy = VkBufferCopy.calloc(1, stack);
            modelCopy.get(0)
                    .srcOffset(modelOffset)
                    .dstOffset(modelOffset)
                    .size(modelBytes);
            VK10.vkCmdCopyBuffer(commandBuffer, sourceBuffer, destinationBuffer, modelCopy);
        }
    }

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
