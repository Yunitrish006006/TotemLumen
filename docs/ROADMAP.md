# Totem Lumen Roadmap

## P0 - Vulkan-only bootstrap
Status: **compile-verified**; native Vulkan and Apple Silicon runtime validation pending.

## P1 - Scene extraction
Status: **implementation complete**; runtime validation pending.

## P2 - Materials and CPU scene
Status: **compile/test complete; runtime validation pending**

- Minecraft-independent material registry and 16x16x16 CPU voxel sections.
- Incremental dirty-section rebuilding capped at 2 sections per extraction frame.
- No forced chunk loads.
- All-air rebuild/removal semantics and stale-world cleanup.
- GPU-facing section/material ABI has unit-tested packers.

## P3 - Vulkan GPU scene
Status: **foundation in progress**

Implemented:
- One isolated Mixin accessor exposes Minecraft's already-created `GpuDeviceBackend`.
- `MinecraftVulkanBridge` accepts only `VulkanDevice`; Totem Lumen never creates a second VkDevice.
- Driver/device/queue/VMA diagnostics without exposing native handles to ordinary renderer code.
- Runtime core-Vulkan limit probe for storage-buffer size and compute workgroup limits.
- Hardware-RT extension state is informational/optional; compute RT does not depend on it.
- Stable GPU ABI: 16 KiB uint32 material-ID payload per populated section and 32-byte material metadata records.
- Stable fixed-size GPU section-slot allocator with revision filtering and deterministic incremental updates.
- Native seam ownership rules documented in `docs/VULKAN_INTEROP.md`.

Next:
- Totem-Lumen-owned storage/staging buffer resource types using Minecraft's existing Vulkan device/VMA.
- Bounded upload queue from `RayScene` section revisions to GPU slots.
- Coordinate-to-slot GPU lookup structure for P4 traversal.
- Timestamp/debug-label instrumentation.

Important: public Blaze3D 26.2 has no compute-dispatch abstraction, so P4 compute dispatch will live behind this same narrow Vulkan seam. No OpenGL path will be added.

## P4 - Vulkan compute voxel traversal
- Vulkan compute pipeline.
- 3D DDA traversal.
- Chunk/section empty-space skipping.
- Debug output: hit distance, normal, material ID, voxel position, traversal step count.

Exit criteria: center-screen debug rays match Minecraft blocks across chunk boundaries, negative coordinates, and world-height edges.

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
