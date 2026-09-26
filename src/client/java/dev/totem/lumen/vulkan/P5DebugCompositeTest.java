package dev.totem.lumen.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.vulkan.resource.VulkanOwnedBuffer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

/**
 * P5B integration gate for Vulkan compute -> Minecraft-owned GpuTexture -> HUD composite.
 *
 * <p>This deliberately uses a deterministic gradient instead of the voxel DDA image. P5A already
 * validates the full-frame DDA grid; keeping this gate simple isolates image-copy / texture-view /
 * GUI-composite failures from ray traversal failures. Once both gates pass, the production debug
 * renderer can wire the DDA pixel buffer directly into the same texture path.</p>
 */
public final class P5DebugCompositeTest {
    private static final int WIDTH = 160;
    private static final int HEIGHT = 90;
    private static final int PIXEL_BYTES = WIDTH * HEIGHT * Integer.BYTES;

    private static final String SHADER = """
            #version 450
            layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
            layout(set = 0, binding = 0, std430) buffer PixelBuffer {
                uint rgba[];
            } pixels;

            uint packRgba(uvec4 c) {
                return (c.r & 255u)
                    | ((c.g & 255u) << 8u)
                    | ((c.b & 255u) << 16u)
                    | ((c.a & 255u) << 24u);
            }

            void main() {
                uvec2 p = gl_GlobalInvocationID.xy;
                if (p.x >= 160u || p.y >= 90u) return;

                uint r = (p.x * 255u) / 159u;
                uint g = (p.y * 255u) / 89u;
                uint checker = (((p.x / 10u) + (p.y / 10u)) & 1u) * 64u;
                uint b = 96u + checker;
                pixels.rgba[p.y * 160u + p.x] = packRgba(uvec4(r, g, b, 255u));
            }
            """;

    private static boolean attempted;
    private static volatile boolean passed;
    private static volatile GpuTextureView debugView;
    private static GpuTexture debugTexture;

    private P5DebugCompositeTest() {
    }

    public static void runOnceOnRenderThread() {
        if (attempted || !P5DebugRayGridTest.passed()) {
            return;
        }

        VulkanCapabilities capabilities = dev.totem.lumen.render.RendererBootstrap.vulkanCapabilities();
        var device = MinecraftVulkanBridge.currentDevice();
        if (capabilities == null || device == null || !capabilities.canUseMinecraftFrameSubmissionForCompute()) {
            return;
        }

        attempted = true;

        VulkanFrameCommandPool commandPool = null;
        VulkanOwnedBuffer pixels = null;
        VulkanComputeProgram program = null;
        GpuTexture texture = null;
        GpuTextureView view = null;
        try {
            commandPool = new VulkanFrameCommandPool(device);
            pixels = VulkanOwnedBuffer.createStorage(device, PIXEL_BYTES);
            program = VulkanComputeProgram.create(device, "totem_lumen_p5_composite.comp", SHADER, pixels);

            texture = RenderSystem.getDevice().createTexture(
                    "Totem Lumen P5 debug target",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA8_UNORM,
                    WIDTH,
                    HEIGHT,
                    1,
                    1
            );
            view = RenderSystem.getDevice().createTextureView(texture);
            if (!(texture instanceof VulkanGpuTexture vulkanTexture)) {
                throw new IllegalStateException("P5 debug target is not backed by VulkanGpuTexture");
            }

            VulkanFrameCommandPool finalCommandPool = commandPool;
            VulkanOwnedBuffer finalPixels = pixels;
            VulkanComputeProgram finalProgram = program;
            GpuTexture finalTexture = texture;
            GpuTextureView finalView = view;

            VulkanFrameComputeBatch batch = VulkanFrameComputeBatch.begin(device, capabilities, commandPool);
            recordCommands(batch.commandBuffer(), program, pixels.vkBuffer(), vulkanTexture.vkImage());
            batch.finishAndEnqueue(() -> finish(
                    finalView, finalTexture, finalProgram, finalPixels, finalCommandPool
            ));

            TotemLumenClient.LOGGER.info(
                    "P5 debug composite submitted: {}x{} Vulkan compute -> GpuTexture",
                    WIDTH, HEIGHT
            );
        } catch (Throwable failure) {
            TotemLumenClient.LOGGER.error("P5 debug composite setup failed", failure);
            closeQuietly(view);
            closeQuietly(texture);
            closeQuietly(program);
            closeQuietly(pixels);
            closeQuietly(commandPool);
        }
    }

    private static void recordCommands(
            VkCommandBuffer commandBuffer,
            VulkanComputeProgram program,
            long pixelBuffer,
            long image
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindPipeline(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, program.pipeline());
            VK10.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    program.pipelineLayout(),
                    0,
                    stack.longs(program.descriptorSet()),
                    null
            );
            VK10.vkCmdDispatch(commandBuffer, (WIDTH + 7) / 8, (HEIGHT + 7) / 8, 1);

            VkBufferMemoryBarrier.Buffer computeToCopy = VkBufferMemoryBarrier.calloc(1, stack);
            computeToCopy.get(0)
                    .sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(pixelBuffer)
                    .offset(0)
                    .size(PIXEL_BYTES);
            VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0,
                    null,
                    computeToCopy,
                    null
            );

            VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
            copy.get(0)
                    .bufferOffset(0)
                    .bufferRowLength(WIDTH)
                    .bufferImageHeight(HEIGHT);
            copy.get(0).imageSubresource()
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);
            copy.get(0).imageOffset().set(0, 0, 0);
            copy.get(0).imageExtent().set(WIDTH, HEIGHT, 1);
            VK10.vkCmdCopyBufferToImage(
                    commandBuffer,
                    pixelBuffer,
                    image,
                    VK10.VK_IMAGE_LAYOUT_GENERAL,
                    copy
            );

            VkImageMemoryBarrier.Buffer imageReady = VkImageMemoryBarrier.calloc(1, stack);
            imageReady.get(0)
                    .sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(image);
            imageReady.get(0).subresourceRange()
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);
            VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0,
                    null,
                    null,
                    imageReady
            );
        }
    }

    private static void finish(
            GpuTextureView view,
            GpuTexture texture,
            VulkanComputeProgram program,
            VulkanOwnedBuffer pixels,
            VulkanFrameCommandPool commandPool
    ) {
        try {
            debugTexture = texture;
            debugView = view;
            passed = true;
            TotemLumenClient.LOGGER.info(
                    "P5 debug composite READY: Vulkan debug texture is available to the HUD overlay"
            );
        } catch (Throwable failure) {
            TotemLumenClient.LOGGER.error("P5 debug composite FAILED", failure);
            closeQuietly(view);
            closeQuietly(texture);
        } finally {
            closeQuietly(program);
            closeQuietly(pixels);
            closeQuietly(commandPool);
        }
    }

    public static void drawHud(GuiGraphicsExtractor graphics) {
        GpuTextureView view = debugView;
        if (!passed || view == null || view.isClosed()) {
            return;
        }

        graphics.blit(
                view,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST),
                0,
                0,
                graphics.guiWidth(),
                graphics.guiHeight(),
                0.0f,
                1.0f,
                1.0f,
                0.0f
        );
    }

    public static boolean passed() {
        return passed;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception exception) {
            TotemLumenClient.LOGGER.warn("Failed to close P5 composite resource", exception);
        }
    }
}
