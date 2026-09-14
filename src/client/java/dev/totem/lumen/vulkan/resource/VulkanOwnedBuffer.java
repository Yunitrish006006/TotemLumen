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

/** A VkBuffer allocation owned by Totem Lumen while borrowing Minecraft's device/VMA allocator. */
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

    /** Device-preferred compute storage buffer, also usable as copy source/destination. */
    public static VulkanOwnedBuffer createStorage(VulkanDevice device, long size) {
        return create(
                device,
                size,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                        | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                        | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE,
                0,
                false
        );
    }

    /** Persistently mapped sequential-write staging buffer. */
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

    /** Persistently mapped CPU-readback buffer. */
    public static VulkanOwnedBuffer createReadback(VulkanDevice device, long size) {
        return create(
                device,
                size,
                VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                Vma.VMA_MEMORY_USAGE_AUTO,
                Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT
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
                    device.vma(), bufferInfo, allocationInfo, bufferPointer, allocationPointer, resultInfo
            );
            if (result != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vmaCreateBuffer failed with VkResult " + result);
            }

            bufferHandle = bufferPointer.get(0);
            allocationHandle = allocationPointer.get(0);
            long mappedPointer = hostVisible ? resultInfo.pMappedData() : 0L;
            if (hostVisible && mappedPointer == 0L) {
                throw new IllegalStateException("VMA created a host-visible buffer without a mapped pointer");
            }

            return new VulkanOwnedBuffer(
                    device.vma(), bufferHandle, allocationHandle, mappedPointer, size, usage, hostVisible
            );
        } catch (Throwable failure) {
            if (bufferHandle != 0L) {
                Vma.vmaDestroyBuffer(device.vma(), bufferHandle, allocationHandle);
            }
            throw failure;
        }
    }

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

    public void flush(long offset, long length) {
        ensureOpen();
        requireHostRange(offset, length);
        Vma.vmaFlushAllocation(vma, allocation, offset, length);
    }

    public void invalidate(long offset, long length) {
        ensureOpen();
        requireHostRange(offset, length);
        Vma.vmaInvalidateAllocation(vma, allocation, offset, length);
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

    private void requireHostRange(long offset, long length) {
        if (!hostVisible) {
            throw new IllegalStateException("Buffer is not host-visible");
        }
        checkRange(offset, length);
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
