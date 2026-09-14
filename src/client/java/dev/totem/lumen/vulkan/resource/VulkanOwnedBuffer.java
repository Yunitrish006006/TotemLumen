package dev.totem.lumen.vulkan.resource;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;

/**
 * A VkBuffer allocation owned exclusively by Totem Lumen while borrowing Minecraft's existing
 * Vulkan device and VMA allocator.
 *
 * <p>This class never destroys the device or allocator. Callers must close every owned buffer before
 * Minecraft tears down its Vulkan backend.</p>
 */
public final class VulkanOwnedBuffer implements AutoCloseable {
    private final long vma;
    private final long vkBuffer;
    private final long allocation;
    private final long mappedPointer;
    private final long size;
    private final int usage;
    private final boolean hostVisible;
    private boolean closed;

    private VulkanOwnedBuffer(
            long vma,
            long vkBuffer,
            long allocation,
            long mappedPointer,
            long size,
            int usage,
            boolean hostVisible
    ) {
        this.vma = vma;
        this.vkBuffer = vkBuffer;
        this.allocation = allocation;
        this.mappedPointer = mappedPointer;
        this.size = size;
        this.usage = usage;
        this.hostVisible = hostVisible;
    }

    /** Device-preferred buffer used as a compute storage buffer and transfer destination. */
    public static VulkanOwnedBuffer createStorage(VulkanDevice device, long size) {
        return create(
                device,
                size,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE,
                0,
                false
        );
    }

    /**
     * Persistently mapped staging buffer optimized for sequential CPU writes.
     *
     * <p>VMA decides the actual memory type. On unified-memory GPUs such as Apple Silicon this can
     * naturally resolve to shared physical memory while preserving the same Vulkan API path.</p>
     */
    public static VulkanOwnedBuffer createUpload(VulkanDevice device, long size) {
        return create(
                device,
                size,
                VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                Vma.VMA_MEMORY_USAGE_AUTO,
                Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
                        | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT,
                true
        );
    }

    private static VulkanOwnedBuffer create(
            VulkanDevice device,
            long size,
            int usage,
            int memoryUsage,
            int allocationFlags,
            boolean hostVisible
    ) {
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size must be positive: " + size);
        }
        if (device.vma() == 0L) {
            throw new IllegalStateException("Minecraft Vulkan device has no VMA allocator");
        }

        long bufferHandle = 0L;
        long allocationHandle = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(usage)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(memoryUsage)
                    .flags(allocationFlags);

            LongBuffer bufferPointer = stack.mallocLong(1);
            PointerBuffer allocationPointer = stack.mallocPointer(1);
            VmaAllocationInfo resultInfo = VmaAllocationInfo.calloc(stack);

            int result = Vma.vmaCreateBuffer(
                    device.vma(),
                    bufferInfo,
                    allocationInfo,
                    bufferPointer,
                    allocationPointer,
                    resultInfo
            );
            if (result != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vmaCreateBuffer failed with VkResult " + result);
            }

            bufferHandle = bufferPointer.get(0);
            allocationHandle = allocationPointer.get(0);
            long mappedPointer = hostVisible ? resultInfo.pMappedData() : 0L;
            if (hostVisible && mappedPointer == 0L) {
                throw new IllegalStateException("VMA created an upload buffer without a mapped pointer");
            }

            return new VulkanOwnedBuffer(
                    device.vma(),
                    bufferHandle,
                    allocationHandle,
                    mappedPointer,
                    size,
                    usage,
                    hostVisible
            );
        } catch (Throwable failure) {
            if (bufferHandle != 0L) {
                Vma.vmaDestroyBuffer(device.vma(), bufferHandle, allocationHandle);
            }
            throw failure;
        }
    }

    /** Returns a little-endian view over the persistent upload mapping. */
    public ByteBuffer mappedView() {
        ensureOpen();
        if (!hostVisible || mappedPointer == 0L) {
            throw new IllegalStateException("Buffer is not host-visible");
        }
        if (size > Integer.MAX_VALUE) {
            throw new IllegalStateException("Mapped ByteBuffer view is limited to 2 GiB: " + size);
        }
        return MemoryUtil.memByteBuffer(mappedPointer, Math.toIntExact(size)).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Flushes host writes for non-coherent memory. Safe on coherent memory as well. */
    public void flush(long offset, long length) {
        ensureOpen();
        if (!hostVisible) {
            throw new IllegalStateException("Cannot flush a non-host-visible buffer");
        }
        checkRange(offset, length);
        Vma.vmaFlushAllocation(vma, allocation, offset, length);
    }

    public long vkBuffer() {
        ensureOpen();
        return vkBuffer;
    }

    public long size() {
        return size;
    }

    public int usage() {
        return usage;
    }

    public boolean hostVisible() {
        return hostVisible;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Vma.vmaDestroyBuffer(vma, vkBuffer, allocation);
    }

    private void checkRange(long offset, long length) {
        if (offset < 0 || length < 0 || offset > size || length > size - offset) {
            throw new IllegalArgumentException(
                    "Range [" + offset + ", " + (offset + length) + ") exceeds buffer size " + size
            );
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Vulkan buffer is already closed");
        }
    }
}
