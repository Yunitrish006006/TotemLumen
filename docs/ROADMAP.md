# Totem Lumen Roadmap

## P0 - Vulkan-only bootstrap
Status: **Apple Silicon runtime verified on Apple M4 / MoltenVK 1.4.2**; native Windows/Linux Vulkan validation still pending.

## P1 - Scene extraction
Status: **implementation complete and exercised in-world on Apple M4**; broader stress/runtime validation still pending.

## P2 - Materials and CPU scene
Status: **compile/test complete and exercised in-world on Apple M4**; detailed material/scene correctness validation still pending.

## P3 - Vulkan GPU scene
Status: **interop/memory/command foundation runtime-verified on Apple M4 / MoltenVK**

Implemented:
- Borrow Minecraft's existing `VulkanDevice`; never create a second VkDevice/MoltenVK device.
- Core limits and queue-family capability probing.
- Stable section/material GPU ABI and slot allocator.
- Totem-owned VMA storage/upload/readback buffers using Minecraft's allocator.
- Totem-owned graphics-family command pool.
- Compute command buffers are inserted into Minecraft's existing Vulkan submission timeline.
- Minecraft GPU fences recycle command buffers without per-frame queue-idle waits.
- CI publishes a development jar on every successful main build.

Apple M4 validation:
- Minecraft 26.2 Vulkan backend accepted.
- MoltenVK 1.4.2 detected.
- Graphics queue advertises compute support.
- Storage buffer / compute limits pass baseline checks.

## P4 - Vulkan compute voxel traversal
Status: **compute smoke test passed on Apple M4; real-world 3D DDA runtime validation in progress**

Completed gate:
- Runtime compile a tiny compute shader using shaderc already bundled by Minecraft 26.2.
- Dispatch 256 uint writes into a storage buffer.
- Barrier and copy to a mapped readback buffer.
- Validate all 256 values after Minecraft's GPU fence completes.
- Apple M4 / MoltenVK result: **PASSED**.
- Fixed descriptor update bug by explicitly setting `VkWriteDescriptorSet.descriptorCount(1)`.

Current DDA gate:
- Upload up to 64 real populated Minecraft sections into a compact Vulkan debug scene.
- GPU performs negative-coordinate-safe section lookup and 3D DDA traversal.
- Dispatch two rays:
  - deterministic synthetic ray from an air voxel into an adjacent known-solid voxel;
  - player camera-center ray for live diagnostics.
- Read back and log hit/miss, voxel coordinates, material ID, face normal, distance, and traversal steps.
- The synthetic ray must match the CPU-known target before P4 traversal is considered complete.

After DDA correctness passes:
- Replace linear debug section lookup with the permanent coordinate -> stable slot lookup.
- Add full-screen debug visualization for material ID / normal / distance / step heatmap.

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
