package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.render.RendererBootstrap;
import dev.totem.lumen.render.RendererState;
import dev.totem.lumen.vulkan.resource.VulkanOwnedBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

/** One-shot runtime validation of shaderc -> Vulkan compute -> storage buffer -> readback. */
public final class P4ComputeSmokeTest {
    private static final int VALUE_COUNT = 256;
    private static final int BYTE_COUNT = VALUE_COUNT * Integer.BYTES;
    private static final int MAGIC = 0x4C554D00; // "LUM\0"

    private static final String SHADER = """
            #version 450
            layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;
            layout(set = 0, binding = 0, std430) buffer OutputBuffer {
                uint values[];
            } outputBuffer;
            void main() {
                uint i = gl_GlobalInvocationID.x;
                if (i < 256u) {
                    outputBuffer.values[i] = 0x4C554D00u ^ i;
                }
            }
            """;

    private static boolean attempted;
    private static volatile boolean passed;

    private P4ComputeSmokeTest() {
    }

    public static void runOnceOnRenderThread() {
        if (attempted || RendererBootstrap.state() != RendererState.READY_FOR_SCENE_EXTRACTION) {
            return;
        }

        VulkanCapabilities capabilities = RendererBootstrap.vulkanCapabilities();
        var device = MinecraftVulkanBridge.currentDevice();
        if (capabilities == null || device == null || !capabilities.canUseMinecraftFrameSubmissionForCompute()) {
            return;
        }
        attempted = true;

        VulkanFrameCommandPool commandPool = null;
        VulkanOwnedBuffer output = null;
        VulkanOwnedBuffer readback = null;
        VulkanComputeProgram program = null;
        try {
            commandPool = new VulkanFrameCommandPool(device);
            output = VulkanOwnedBuffer.createStorage(device, BYTE_COUNT);
            readback = VulkanOwnedBuffer.createReadback(device, BYTE_COUNT);
            program = VulkanComputeProgram.create(device, "totem_lumen_smoke.comp", SHADER, output);

            VulkanFrameCommandPool finalCommandPool = commandPool;
            VulkanOwnedBuffer finalOutput = output;
            VulkanOwnedBuffer finalReadback = readback;
            VulkanComputeProgram finalProgram = program;

            VulkanFrameComputeBatch batch = VulkanFrameComputeBatch.begin(device, capabilities, commandPool);
            recordSmokeCommands(
                    batch.commandBuffer(), program, output.vkBuffer(), readback.vkBuffer()
            );
            batch.finishAndEnqueue(() -> finishValidation(
                    finalReadback, finalProgram, finalOutput, finalCommandPool
            ));

            TotemLumenClient.LOGGER.info("P4 Vulkan compute smoke test submitted");
        } catch (Throwable failure) {
            TotemLumenClient.LOGGER.error("P4 Vulkan compute smoke test setup failed", failure);
            closeQuietly(program);
            closeQuietly(output);
            closeQuietly(readback);
            closeQuietly(commandPool);
        }
    }

    private static void recordSmokeCommands(
            VkCommandBuffer commandBuffer,
            VulkanComputeProgram program,
            long outputBuffer,
            long readbackBuffer
    ) {
        VK10.vkCmdBindPipeline(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, program.pipeline());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    program.pipelineLayout(),
                    0,
                    stack.longs(program.descriptorSet()),
                    null
            );
            VK10.vkCmdDispatch(commandBuffer, 4, 1, 1);

            VkBufferMemoryBarrier.Buffer computeToCopy = VkBufferMemoryBarrier.calloc(1, stack);
            computeToCopy.get(0)
                    .sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(outputBuffer)
                    .offset(0)
                    .size(BYTE_COUNT);
            VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0,
                    null,
                    computeToCopy,
                    null
            );

            VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack);
            copy.get(0).srcOffset(0).dstOffset(0).size(BYTE_COUNT);
            VK10.vkCmdCopyBuffer(commandBuffer, outputBuffer, readbackBuffer, copy);

            VkBufferMemoryBarrier.Buffer copyToHost = VkBufferMemoryBarrier.calloc(1, stack);
            copyToHost.get(0)
                    .sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(readbackBuffer)
                    .offset(0)
                    .size(BYTE_COUNT);
            VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_HOST_BIT,
                    0,
                    null,
                    copyToHost,
                    null
            );
        }
    }

    private static void finishValidation(
            VulkanOwnedBuffer readback,
            VulkanComputeProgram program,
            VulkanOwnedBuffer output,
            VulkanFrameCommandPool commandPool
    ) {
        try {
            readback.invalidate(0, BYTE_COUNT);
            ByteBuffer values = readback.mappedView();
            for (int i = 0; i < VALUE_COUNT; i++) {
                int expected = MAGIC ^ i;
                int actual = values.getInt(i * Integer.BYTES);
                if (actual != expected) {
                    throw new IllegalStateException(
                            "Compute readback mismatch at " + i + ": expected 0x"
                                    + Integer.toHexString(expected) + ", got 0x" + Integer.toHexString(actual)
                    );
                }
            }
            passed = true;
            TotemLumenClient.LOGGER.info(
                    "P4 Vulkan compute smoke test PASSED: shader/dispatch/barrier/readback validated for {} values",
                    VALUE_COUNT
            );
        } catch (Throwable failure) {
            TotemLumenClient.LOGGER.error("P4 Vulkan compute smoke test FAILED", failure);
        } finally {
            closeQuietly(program);
            closeQuietly(output);
            closeQuietly(readback);
            closeQuietly(commandPool);
        }
    }

    public static boolean passed() {
        return passed;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception exception) {
            TotemLumenClient.LOGGER.warn("Failed to close P4 smoke-test resource", exception);
        }
    }
}
