# Vulkan Interop Boundary

## Decision

Totem Lumen does **not** create its own Vulkan instance, physical-device selection, logical device, swapchain, or presentation loop.

Minecraft 26.2 owns those objects. Totem Lumen borrows the existing `com.mojang.blaze3d.vulkan.VulkanDevice` through one isolated Mixin accessor on `GpuDevice.backend`.

This rule applies equally to native Vulkan on Windows/Linux and Minecraft's MoltenVK-to-Metal path on macOS/Apple Silicon.

## Why a narrow native seam is required

Minecraft 26.2's public `GpuDeviceBackend` exposes buffers, textures, render-pipeline compilation and timestamp queries. Its public `CommandEncoderBackend` exposes copies, clears, render passes, fences and timestamps. It does not expose a compute-pipeline type or compute dispatch operation.

Totem Lumen's baseline renderer requires Vulkan compute for voxel traversal, so the missing compute/storage capability is the only reason native Vulkan interop is permitted.

References:

- Minecraft 26.2 `GpuDeviceBackend`: `com.mojang.blaze3d.systems.GpuDeviceBackend`
- Minecraft 26.2 `CommandEncoderBackend`: `com.mojang.blaze3d.systems.CommandEncoderBackend`
- Minecraft 26.2 `VulkanDevice`: `com.mojang.blaze3d.vulkan.VulkanDevice`
- Fabric rendering guidance: https://docs.fabricmc.net/develop/rendering/basic-concepts

## Ownership rules

Minecraft owns:
- `VkInstance`
- `VkPhysicalDevice` selection
- `VkDevice`
- Minecraft's VMA allocator
- graphics/compute/transfer queues
- swapchain/surface/presentation
- backend shutdown order

Totem Lumen owns only resources it explicitly creates for its ray-tracing workload. It must destroy those resources before Minecraft destroys the device. It must never call `close()` on `VulkanDevice`, destroy Minecraft's allocator, wait-idle globally as a normal frame operation, or replace the swapchain.

## Apple Silicon

No Apple-specific renderer fork is allowed. The same Vulkan calls are used against the `VulkanDevice` that Minecraft created. On macOS Minecraft supplies the MoltenVK/Metal translation layer. Feature decisions are capability-driven; platform/driver string detection is diagnostic only.

## ABI baseline

One CPU section is 16x16x16 voxels and is initially uploaded as 4096 little-endian unsigned 32-bit material IDs (16 KiB per populated section). This is intentionally simple for P3/P4 correctness. Compression/palette encoding can be introduced only after profiling demonstrates the memory/bandwidth need.

The baseline material record is 32 bytes and std430-friendly. The source registry identifier never enters the GPU buffer.
