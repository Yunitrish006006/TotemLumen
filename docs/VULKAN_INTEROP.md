# Vulkan Interop Boundary

## Decision

Totem Lumen does **not** create its own Vulkan instance, physical-device selection, logical device, swapchain, or presentation loop.

Minecraft 26.2 owns those objects. Totem Lumen borrows the existing `com.mojang.blaze3d.vulkan.VulkanDevice` through one isolated Mixin accessor on `GpuDevice.backend`.

This rule applies equally to native Vulkan on Windows/Linux and Minecraft's MoltenVK-to-Metal path on macOS/Apple Silicon.

## Why a narrow native seam is required

Minecraft 26.2's public generic Blaze3D interfaces do not expose a compute-pipeline or compute-dispatch API. Totem Lumen therefore records native Vulkan compute commands, but keeps queue submission inside Minecraft whenever possible.

## Preferred command-submission strategy

1. Query the real Vulkan queue-family properties.
2. If Minecraft's graphics queue supports compute, create one Totem-Lumen-owned command pool for that same graphics queue family.
3. Record copy/compute/barrier commands into Totem Lumen command buffers.
4. End the command buffer and insert it into Minecraft's current Vulkan submission with `VulkanCommandEncoder.execute(...)`.
5. Register a Minecraft GPU fence callback and recycle the command buffer only after completion.
6. Never `vkQueueWaitIdle` per frame.

A separate compute-queue executor is a fallback only for devices whose graphics queue cannot dispatch compute. It will require explicit synchronization and is deliberately not the default path.

This avoids depending on Minecraft-private transient-command-buffer allocation methods, while retaining Minecraft's submission timeline and MoltenVK/Metal frame lifecycle.

## Ownership rules

Minecraft owns:
- `VkInstance`
- `VkPhysicalDevice` selection
- `VkDevice`
- Minecraft's VMA allocator
- graphics/compute/transfer queues
- VulkanCommandEncoder submission timeline
- swapchain/surface/presentation
- backend shutdown order

Totem Lumen owns:
- each VMA buffer allocation it creates
- its graphics-family compute command pool
- its descriptor/pipeline resources added in P4

Totem Lumen must never:
- call `VulkanDevice.close()`
- destroy Minecraft's VMA allocator
- destroy a Minecraft queue/surface/swapchain
- use `vkQueueWaitIdle` as a per-frame synchronization strategy
- create a second Vulkan/MoltenVK device for the renderer

`VulkanResourceScope` owns only Totem-Lumen-created resources and destroys them in reverse creation order.

## Baseline memory plan

Device scene buffers use `VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT` and VMA `AUTO_PREFER_DEVICE`. Upload buffers use `VK_BUFFER_USAGE_TRANSFER_SRC_BIT`, VMA `AUTO`, persistent mapping and sequential-write host access.

On discrete GPUs this normally behaves as device memory plus staging memory. On unified-memory hardware such as Apple Silicon, VMA can choose an appropriate shared memory type while Totem Lumen retains exactly the same Vulkan API path.

## Apple Silicon

No Apple-specific renderer fork is allowed. The same Vulkan calls are used against the `VulkanDevice` that Minecraft created. On macOS Minecraft supplies the MoltenVK/Metal translation layer. Feature decisions are capability-driven; platform/driver string detection is diagnostic only.

## ABI baseline

One CPU section is 16x16x16 voxels and is initially uploaded as 4096 little-endian unsigned 32-bit material IDs (16 KiB per populated section). This is intentionally simple for P3/P4 correctness. Compression/palette encoding can be introduced only after profiling demonstrates the memory/bandwidth need.

The baseline material record is 32 bytes and std430-friendly. The source registry identifier never enters the GPU buffer.
