package dev.totem.lumen.mixin;

import dev.totem.lumen.scene.SectionSnapshot;
import dev.totem.lumen.vulkan.P12FullBasePipeline;
import dev.totem.lumen.vulkan.P14EFluidGpuUploader;
import dev.totem.lumen.vulkan.P14ModelMeshGpuUploader;
import dev.totem.lumen.vulkan.P16MultipassReflection;
import dev.totem.lumen.vulkan.P17DynamicEntityGpuUploader;
import dev.totem.lumen.vulkan.P18PbrTextureGpuUploader;
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

/** Shared scene-tail upload hooks plus the optional P16 reflection dispatch. */
@Mixin(targets = "dev.totem.lumen.vulkan.P5StableLookupRenderer", remap = false)
public abstract class P5StableLookupRendererMixin {
    private static final long CAMERA_UPLOAD_MAX_BYTES = 4096L;

    @Inject(method = "packLookupSectionsAndLights", at = @At("TAIL"))
    private static void totemLumen$packGeometryTails(
            ByteBuffer buffer,
            List<SectionSnapshot> sections,
            CallbackInfo ci
    ) {
        P14ModelMeshGpuUploader.pack(buffer);
        P17DynamicEntityGpuUploader.pack(buffer);
        P14EFluidGpuUploader.pack(buffer, sections);
        P18PbrTextureGpuUploader.pack(buffer);
    }

    @Redirect(
            method = "runOnRenderThread",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/totem/lumen/vulkan/resource/VulkanOwnedBuffer;flush(JJ)V"
            )
    )
    private static void totemLumen$flushSceneAndGeometryTails(
            VulkanOwnedBuffer upload,
            long offset,
            long length
    ) {
        boolean modelChanged = P14ModelMeshGpuUploader.packIfDirty(upload.mappedView());
        boolean entityChanged = P17DynamicEntityGpuUploader.packIfDirty(upload.mappedView());
        boolean fluidChanged = P14EFluidGpuUploader.packIfDirty(upload.mappedView());
        boolean pbrChanged = P18PbrTextureGpuUploader.packIfDirty(upload.mappedView());
        upload.flush(offset, length);

        boolean fullSceneUpload = length > CAMERA_UPLOAD_MAX_BYTES;
        if (fullSceneUpload || modelChanged) {
            flushTail(
                    upload,
                    P14ModelMeshGpuUploader.lastBaseByteOffset(),
                    P14ModelMeshGpuUploader.lastCopyBytes()
            );
        }
        if (fullSceneUpload || entityChanged) {
            flushP17Tails(upload);
        }
        if (fullSceneUpload || fluidChanged) {
            flushTail(
                    upload,
                    P14EFluidGpuUploader.lastBaseByteOffset(),
                    P14EFluidGpuUploader.lastCopyBytes()
            );
        }
        if (fullSceneUpload || pbrChanged) {
            flushTail(
                    upload,
                    P18PbrTextureGpuUploader.lastBaseByteOffset(),
                    P18PbrTextureGpuUploader.lastCopyBytes()
            );
        }
    }

    @Redirect(
            method = "recordCommands",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VK10;vkCmdCopyBuffer(Lorg/lwjgl/vulkan/VkCommandBuffer;JJLorg/lwjgl/vulkan/VkBufferCopy$Buffer;)V"
            )
    )
    private static void totemLumen$copySceneAndGeometryTails(
            VkCommandBuffer commandBuffer,
            long sourceBuffer,
            long destinationBuffer,
            VkBufferCopy.Buffer regions
    ) {
        VK10.vkCmdCopyBuffer(commandBuffer, sourceBuffer, destinationBuffer, regions);

        if (P14ModelMeshGpuUploader.consumeCopyPending()) {
            copyTail(
                    commandBuffer,
                    sourceBuffer,
                    destinationBuffer,
                    P14ModelMeshGpuUploader.lastBaseByteOffset(),
                    P14ModelMeshGpuUploader.lastCopyBytes()
            );
        }
        if (P17DynamicEntityGpuUploader.consumeCopyPending()) {
            copyP17Tails(commandBuffer, sourceBuffer, destinationBuffer);
        }
        if (P14EFluidGpuUploader.consumeCopyPending()) {
            copyTail(
                    commandBuffer,
                    sourceBuffer,
                    destinationBuffer,
                    P14EFluidGpuUploader.lastBaseByteOffset(),
                    P14EFluidGpuUploader.lastCopyBytes()
            );
        }
        if (P18PbrTextureGpuUploader.consumeCopyPending()) {
            copyTail(
                    commandBuffer,
                    sourceBuffer,
                    destinationBuffer,
                    P18PbrTextureGpuUploader.lastBaseByteOffset(),
                    P18PbrTextureGpuUploader.lastCopyBytes()
            );
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
    private static void totemLumen$shutdownStagedPipelines(CallbackInfo ci) {
        P12FullBasePipeline.shutdown();
    }

    private static void flushP17Tails(VulkanOwnedBuffer upload) {
        flushTail(
                upload,
                P17DynamicEntityGpuUploader.lastMetadataByteOffset(),
                P17DynamicEntityGpuUploader.lastMetadataCopyBytes()
        );
        flushTail(
                upload,
                P17DynamicEntityGpuUploader.lastTextureByteOffset(),
                P17DynamicEntityGpuUploader.lastTextureCopyBytes()
        );
        flushTail(
                upload,
                P17DynamicEntityGpuUploader.lastQuadByteOffset(),
                P17DynamicEntityGpuUploader.lastQuadCopyBytes()
        );
    }

    private static void copyP17Tails(
            VkCommandBuffer commandBuffer,
            long sourceBuffer,
            long destinationBuffer
    ) {
        copyTail(
                commandBuffer,
                sourceBuffer,
                destinationBuffer,
                P17DynamicEntityGpuUploader.lastMetadataByteOffset(),
                P17DynamicEntityGpuUploader.lastMetadataCopyBytes()
        );
        copyTail(
                commandBuffer,
                sourceBuffer,
                destinationBuffer,
                P17DynamicEntityGpuUploader.lastTextureByteOffset(),
                P17DynamicEntityGpuUploader.lastTextureCopyBytes()
        );
        copyTail(
                commandBuffer,
                sourceBuffer,
                destinationBuffer,
                P17DynamicEntityGpuUploader.lastQuadByteOffset(),
                P17DynamicEntityGpuUploader.lastQuadCopyBytes()
        );
    }

    private static void flushTail(VulkanOwnedBuffer upload, long offset, long bytes) {
        if (offset >= 0L && bytes > 0L) upload.flush(offset, bytes);
    }

    private static void copyTail(
            VkCommandBuffer commandBuffer,
            long sourceBuffer,
            long destinationBuffer,
            long offset,
            long bytes
    ) {
        if (offset < 0L || bytes <= 0L) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack);
            copy.get(0)
                    .srcOffset(offset)
                    .dstOffset(offset)
                    .size(bytes);
            VK10.vkCmdCopyBuffer(commandBuffer, sourceBuffer, destinationBuffer, copy);
        }
    }
}
