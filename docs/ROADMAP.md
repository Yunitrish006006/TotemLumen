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

## P5 - Debug visualization
Status: **COMPLETE on Apple M4 / MoltenVK**

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
- Runtime visualization modes: Normal / Material / Distance / Steps.
- F8 cycles visualization mode and can be rebound through Minecraft Controls.
- Camera movement, all four modes, world exit/rejoin, and resource lifecycle were runtime-verified on Apple M4: **PASSED**.

## GPU scene lookup gate
Status: **COMPLETE on Apple M4 / MoltenVK**

Validated:
- Removed the live DDA shader's linear scan over up to 64 sections.
- Reused `GpuSectionSlotAllocator` so resident section voxel payloads keep stable 16 KiB slots across updates.
- Added explicit per-section eviction/allocator reset support for moving selection windows and world lifecycle.
- `GpuSectionLookupTable` uses a 128-bucket open-addressed hash table with 4 words per bucket: `sectionX`, `sectionY`, `sectionZ`, `slot+1`.
- Slot zero remains representable while zero in the fourth word marks an empty bucket.
- GLSL and Java use the same 32-bit hash constants/overflow semantics, including negative coordinates.
- DDA `materialAt()` resolves `sectionCoord -> stable slot -> fixed voxel payload` through the hash table.
- Camera-only frames still upload only the small header; voxel/lookup data is uploaded only when the selected section/revision signature changes.
- CPU tests cover negative coordinates, slot zero, collisions/linear probing, and packed GPU ABI layout.
- Apple M4 runtime: stable live image, F8 modes, world rejoin and lifecycle all remained correct.
- Runtime diagnostic observed `slots=24/64` and `lookupMaxProbe=0` with no Vulkan errors: **PASSED**.
- HUD texture UV orientation was corrected and visually revalidated in `0.1.0-alpha.9`.

## P6 - Hard shadows
Status: **IMPLEMENTED; Apple M4 runtime visual validation pending**

Implemented in `0.1.0-alpha.10`:
- Reuses the stable P5 primary-ray DDA and hashed section lookup.
- Adds a fifth runtime visualization mode: `Hard Shadow`.
- `Hard Shadow` is the default mode for the P6 validation build; F8 can still cycle through all previous debug modes.
- After a primary voxel hit, the shader offsets the hit point away from the surface and launches a second DDA ray toward a fixed directional test light.
- Any blocking voxel produces binary visibility `0`; an unobstructed path produces visibility `1`.
- Lambert `N·L` plus a small ambient floor makes shadowed vs lit surfaces visually distinguishable.
- No soft-shadow sampling, temporal filtering, light culling, emissive lighting, or GI is included yet.
- GLSL/Java CI build: **PASSED**.

Runtime gate:
- Confirm the default image shows directional lighting rather than the old normal debug colors.
- Confirm blocks cast visible hard-edged shadows onto other voxel surfaces.
- Confirm moving the camera keeps shadows stable with no self-shadow acne severe enough to dominate the image.
- Confirm F8 still cycles Hard Shadow / Normal / Material / Distance / Steps correctly.
- Confirm log contains `P6 hard shadow debug READY` and no Vulkan/MoltenVK errors.

## P7-P8 - Direct lighting
- P7: light culling/chunk light lists.
- P8: emissive materials.

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
