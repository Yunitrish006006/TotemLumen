# Vulkan Interop Boundary

## Decision

Totem Lumen does **not** create its own Vulkan instance, physical-device selection, logical device, swapchain, or presentation loop.

Minecraft 26.2 owns those objects. Totem Lumen borrows the existing `com.mojang.blaze3d.vulkan.VulkanDevice` through one isolated Mixin accessor on `GpuDevice.backend`.

This rule applies equally to native Vulkan on Windows/Linux and Minecraft's MoltenVK-to-Metal path on macOS/Apple Silicon.

## Why a narrow native seam is required

Minecraft 26.2's public `GpuDeviceBackend` exposes buffers, textures, render-pipeline compilation and timestamp queries. Its public `CommandEncoderBackend` exposes copies, clears, render passes, fences and timestamps. It does not expose a compute-pipeline type or compute dispatch operation.

Totem Lumen's baseline renderer requires Vulkan compute for voxel traversal, so the missing compute/storage capability is the only reason native Vulkan interop is permitted.

## Ownership rules

Minecraft owns:
- `VkInstance`
- `VkPhysicalDevice` selection
- `VkDevice`
- Minecraft's VMA allocator
- graphics/compute/transfer queues
- swapchain/surface/presentation
- backend shutdown order

Totem Lumen borrows the device and VMA allocator, but owns each allocation it creates with that allocator. `VulkanOwnedBuffer` is the baseline buffer wrapper: its buffer/allocation pair belongs to Totem Lumen and must be destroyed before Minecraft destroys the allocator.

Totem Lumen must never:
- call `VulkanDevice.close()`
- destroy Minecraft's VMA allocator
- destroy a Minecraft queue/surface/swapchain
- use `vkQueueWaitIdle` as a per-frame synchronization strategy
- create a second Vulkan/MoltenVK device for the renderer

`VulkanResourceScope` owns only Totem-Lumen-created resources and destroys them in reverse creation order.

## Baseline memory plan

### Device scene buffer

Usage:
- `VK_BUFFER_USAGE_STORAGE_BUFFER_BIT`
- `VK_BUFFER_USAGE_TRANSFER_DST_BIT`
- VMA `AUTO_PREFER_DEVICE`

This contains voxel/material/lookup data used by compute shaders.

### Upload buffer

Usage:
- `VK_BUFFER_USAGE_TRANSFER_SRC_BIT`
- VMA `AUTO`
- `HOST_ACCESS_SEQUENTIAL_WRITE`
- persistently mapped

On discrete GPUs this normally behaves as staging memory. On unified-memory hardware such as Apple Silicon, VMA can choose an appropriate shared memory type while Totem Lumen retains exactly the same Vulkan code path.

The first implementation deliberately uses exclusive queue-family ownership and plans copies/dispatches on the compute queue. This avoids graphics/compute queue-family ownership transfers until profiling demonstrates a reason to split work across queues.

## Apple Silicon

No Apple-specific renderer fork is allowed. The same Vulkan calls are used against the `VulkanDevice` that Minecraft created. On macOS Minecraft supplies the MoltenVK/Metal translation layer. Feature decisions are capability-driven; platform/driver string detection is diagnostic only.

## ABI baseline

One CPU section is 16x16x16 voxels and is initially uploaded as 4096 little-endian unsigned 32-bit material IDs (16 KiB per populated section). This is intentionally simple for P3/P4 correctness. Compression/palette encoding can be introduced only after profiling demonstrates the memory/bandwidth need.

The baseline material record is 32 bytes and std430-friendly. The source registry identifier never enters the GPU buffer.
