package dev.totem.lumen.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.totem.lumen.vulkan.resource.VulkanOwnedBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/** Minimal single-storage-buffer Vulkan compute pipeline used by the P4 smoke test. */
public final class VulkanComputeProgram implements AutoCloseable {
    private final VulkanDevice device;
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    private final long pipeline;
    private final long descriptorPool;
    private final long descriptorSet;
    private boolean closed;

    private VulkanComputeProgram(
            VulkanDevice device,
            long descriptorSetLayout,
            long pipelineLayout,
            long pipeline,
            long descriptorPool,
            long descriptorSet
    ) {
        this.device = device;
        this.descriptorSetLayout = descriptorSetLayout;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.descriptorPool = descriptorPool;
        this.descriptorSet = descriptorSet;
    }

    public static VulkanComputeProgram create(VulkanDevice device, String name, String glsl, VulkanOwnedBuffer storage) {
        if ("totem_lumen_p12_one_bounce_gi.comp".equals(name)) {
            glsl = P12GiShaderPatch.apply(glsl);
            glsl = P13EndBrightnessPatch.apply(glsl);
        }
        long shaderModule = compileShaderModule(device, name, glsl);
        long descriptorSetLayout = 0L;
        long pipelineLayout = 0L;
        long pipeline = 0L;
        long descriptorPool = 0L;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(1, stack);
            bindings.get(0)
                    .binding(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo setLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pBindings(bindings);
            LongBuffer setLayoutPtr = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(device.vkDevice(), setLayoutInfo, null, setLayoutPtr), "vkCreateDescriptorSetLayout");
            descriptorSetLayout = setLayoutPtr.get(0);

            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(descriptorSetLayout));
            LongBuffer pipelineLayoutPtr = stack.mallocLong(1);
            check(VK10.vkCreatePipelineLayout(device.vkDevice(), pipelineLayoutInfo, null, pipelineLayoutPtr), "vkCreatePipelineLayout");
            pipelineLayout = pipelineLayoutPtr.get(0);

            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule)
                    .pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
            LongBuffer pipelinePtr = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(device.vkDevice(), 0L, pipelineInfo, null, pipelinePtr), "vkCreateComputePipelines");
            pipeline = pipelinePtr.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .maxSets(1)
                    .pPoolSizes(poolSizes);
            LongBuffer poolPtr = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorPool(device.vkDevice(), poolInfo, null, poolPtr), "vkCreateDescriptorPool");
            descriptorPool = poolPtr.get(0);

            VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default()
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorSetLayout));
            LongBuffer setPtr = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(device.vkDevice(), allocateInfo, setPtr), "vkAllocateDescriptorSets");
            long descriptorSet = setPtr.get(0);

            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
            bufferInfo.get(0).buffer(storage.vkBuffer()).offset(0).range(storage.size());
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);
            writes.get(0)
                    .sType$Default()
                    .dstSet(descriptorSet)
                    .dstBinding(0)
                    .descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(bufferInfo);
            VK10.vkUpdateDescriptorSets(device.vkDevice(), writes, null);

            return new VulkanComputeProgram(
                    device, descriptorSetLayout, pipelineLayout, pipeline, descriptorPool, descriptorSet
            );
        } catch (Throwable failure) {
            if (descriptorPool != 0L) VK10.vkDestroyDescriptorPool(device.vkDevice(), descriptorPool, null);
            if (pipeline != 0L) VK10.vkDestroyPipeline(device.vkDevice(), pipeline, null);
            if (pipelineLayout != 0L) VK10.vkDestroyPipelineLayout(device.vkDevice(), pipelineLayout, null);
            if (descriptorSetLayout != 0L) VK10.vkDestroyDescriptorSetLayout(device.vkDevice(), descriptorSetLayout, null);
            throw failure;
        } finally {
            VK10.vkDestroyShaderModule(device.vkDevice(), shaderModule, null);
        }
    }

    private static long compileShaderModule(VulkanDevice device, String name, String source) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        if (compiler == 0L || options == 0L) {
            throw new IllegalStateException("Failed to initialize shaderc");
        }
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, 0, 4202496);
            Shaderc.shaderc_compile_options_set_optimization_level(options, 2);
            long result = Shaderc.shaderc_compile_into_spv(
                    compiler, source, Shaderc.shaderc_compute_shader, name, "main", options
            );
            try {
                if (Shaderc.shaderc_result_get_compilation_status(result) != 0) {
                    throw new IllegalStateException(
                            "Compute shader compilation failed: " + Shaderc.shaderc_result_get_error_message(result)
                    );
                }
                ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                            .sType$Default()
                            .pCode(spirv);
                    LongBuffer modulePtr = stack.mallocLong(1);
                    check(VK10.vkCreateShaderModule(device.vkDevice(), createInfo, null, modulePtr), "vkCreateShaderModule");
                    return modulePtr.get(0);
                }
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    public long pipeline() {
        return pipeline;
    }

    public long pipelineLayout() {
        return pipelineLayout;
    }

    public long descriptorSet() {
        return descriptorSet;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        VK10.vkDestroyDescriptorPool(device.vkDevice(), descriptorPool, null);
        VK10.vkDestroyPipeline(device.vkDevice(), pipeline, null);
        VK10.vkDestroyPipelineLayout(device.vkDevice(), pipelineLayout, null);
        VK10.vkDestroyDescriptorSetLayout(device.vkDevice(), descriptorSetLayout, null);
    }

    private static void check(int result, String operation) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
    }
}
