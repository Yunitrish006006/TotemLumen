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
Status: **COMPLETE on Apple M4 / MoltenVK baseline path**

Validated:
- shaderc -> SPIR-V -> Vulkan compute -> storage buffer -> barriers -> readback.
- 256-value compute smoke test: **PASSED**.
- Real Minecraft section upload into Vulkan storage buffers.
- Negative-coordinate-safe section lookup.
- 3D DDA voxel traversal.
- Deterministic synthetic ray validation against a CPU-known target.
- Latest Apple M4 validation: voxel `(-463, 64, 32)`, material `29`, normal `(0, 1, 0)`, distance `0.5`, steps `1`: **PASSED**.
- Latest camera-center diagnostic also produced a real hit at voxel `(-462, 63, 40)`, material `29`, distance `0.52381134`, steps `1`.

Remaining architecture improvement (not a P4 blocker):
- Replace temporary linear 64-section lookup with the permanent coordinate -> stable slot lookup before production-scale rendering.

## P5 - Debug visualization
Status: **P5A/P5B/P5C runtime-verified on Apple M4; persistent live renderer implemented and awaiting runtime validation**

P5A - full-frame DDA debug grid:
- 160-pixel-wide low-resolution target preserving the current window aspect ratio.
- One Vulkan compute invocation per debug pixel.
- Full camera ray generation from extracted camera quaternion/FOV.
- Debug shader modes implemented: normal, material ID, distance, traversal-step heatmap.
- One-time CPU readback is used only as a correctness gate; production hot path remains GPU-only.
- Latest Apple M4 runtime result: `160x90`, `14400` hits, `0` misses, center RGBA `0xff80ff80`: **PASSED**.

P5B - texture/composite gate:
- Vulkan compute writes an RGBA debug buffer.
- `vkCmdCopyBufferToImage` copies directly into a Minecraft-owned `GpuTexture` backed by `VulkanGpuTexture`.
- Texture remains in the Minecraft Vulkan device / submission timeline; no second device, surface, or swapchain.
- `GpuTextureView` is displayed through Fabric 26.2 `HudElementRegistry` / `GuiGraphicsExtractor.blit`.
- Apple M4 runtime result: `P5 debug composite READY`: **PASSED**.

P5C - real-world DDA image composite:
- Real Minecraft voxel normal-debug image rendered by Vulkan compute.
- Uses the same extracted camera/section model as P5A.
- GPU DDA output stays on GPU and is copied directly into the Minecraft-owned debug texture.
- Apple M4 runtime result: `P5 world DDA composite READY`: **PASSED**.

P5D - persistent live debug renderer:
- Persistent Vulkan upload/storage buffers, compute pipeline, command pool, `GpuTexture`, and texture view.
- At most one debug frame may be in flight; no mapped upload buffer overwrite while the GPU is using it.
- Camera/header data updates per submitted frame.
- Voxel data is only repacked/uploaded when the nearest-section selection or section revision signature changes.
- Dimension/world detach resets the live resources rather than retaining stale scene state.
- Runtime visualization modes: Normal / Material / Distance / Steps.
- F8 cycles visualization mode and can be rebound through Minecraft Controls.
- Compile/CI baseline: **PASSED** after migration to Fabric 26.2 `keymapping` API.
- Apple M4 live runtime validation: **PENDING**.

P5 exit criteria:
- Camera movement updates the debug image continuously without resource churn or Vulkan errors.
- Block edits/section changes become visible after the section revision changes.
- F8 correctly cycles Normal / Material / Distance / Steps.
- Leaving/rejoining a world or changing dimension does not retain stale GPU scene resources.
- Then replace the temporary 64-section linear lookup with the permanent GPU scene/slot lookup before scaling resolution or entering P6.

## P6-P8 - Direct lighting
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
