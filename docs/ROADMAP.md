# Totem Lumen Roadmap

## P0 - Vulkan-only bootstrap
Status: **compile-verified**; native Vulkan and Apple Silicon runtime validation pending.

## P1 - Scene extraction
Status: **implementation complete**; runtime validation pending.

## P2 - Materials and CPU scene
Status: **compile/test complete**; runtime validation pending.

## P3 - Vulkan GPU scene
Status: **interop/memory/command foundation compile-verified; runtime validation pending**

Implemented:
- Borrow Minecraft's existing `VulkanDevice`; never create a second VkDevice/MoltenVK device.
- Core limits and queue-family capability probing.
- Stable section/material GPU ABI and slot allocator.
- Totem-owned VMA storage/upload/readback buffers using Minecraft's allocator.
- Totem-owned graphics-family command pool.
- Compute command buffers are inserted into Minecraft's existing Vulkan submission timeline.
- Minecraft GPU fences recycle command buffers without per-frame queue-idle waits.
- CI publishes a development jar on every successful main build.

## P4 - Vulkan compute voxel traversal
Status: **compute smoke test in progress**

First gate:
- Runtime compile a tiny compute shader using shaderc already bundled by Minecraft 26.2.
- Dispatch 256 uint writes into a storage buffer.
- Barrier and copy to a mapped readback buffer.
- Validate all values after Minecraft's GPU fence completes.
- Same code path on native Vulkan and Apple Silicon/MoltenVK; no extra player install.

After smoke test passes on both targets:
- Replace fixed pattern kernel with 3D DDA voxel traversal.
- Upload section coordinate -> slot lookup.
- Debug output: hit distance, normal, material ID, voxel position, traversal step count.

## P5-P8 - Direct lighting
- Debug visualization.
- Hard shadows.
- Light culling/chunk light lists.
- Emissive materials.

## P9-P12 - Stable GI baseline
- Soft shadow sampling.
- Temporal reprojection/history validation.
- Spatial denoising.
- 1-bounce diffuse GI.

Target for first meaningful public alpha.

## P13-P18 - Scene quality
- Sun/sky/dimension environment lighting.
- Reflection/roughness.
- Glass/water transmission.
- Hybrid geometry for slabs/stairs/cutouts.
- Dynamic entities.
- Resource pack / LabPBR integration.

## P19+ - Optional hardware RT and advanced sampling
- Feature-detected Vulkan acceleration structures and RT pipeline.
- Compute backend remains available and supported.
- ReSTIR/adaptive sampling/dynamic resolution/upscaling considered only after the baseline renderer is stable.
