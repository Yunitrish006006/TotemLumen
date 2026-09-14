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
Status: **COMPLETE on Apple M4 / MoltenVK**

Validated in `0.1.0-alpha.10`:
- Reuses the stable P5 primary-ray DDA and hashed section lookup.
- Adds `Hard Shadow` runtime visualization mode.
- After a primary voxel hit, the shader offsets the hit point away from the surface and launches a second DDA ray toward a fixed directional test light.
- Blocking voxels produce binary visibility `0`; unobstructed paths produce visibility `1`.
- Lambert `N·L` plus a small ambient floor makes shadowed vs lit surfaces visually distinguishable.
- Hard-edged shadow output was visually confirmed on Apple M4 with stable camera movement and acceptable self-shadow behavior: **PASSED**.
- F8 debug mode switching remained functional.

## P7 - Local light culling / section light lists
Status: **COMPLETE on Apple M4 / MoltenVK**

Validated in `0.1.0-alpha.11`:
- Reuses material `emissionLevel` already extracted from Minecraft `BlockState` data; no extra Minecraft lighting-object references are retained.
- `GpuSectionLightLists` scans selected section snapshots for emissive voxels and produces compact point-light candidates.
- Global debug-light capacity is capped at 256 candidates.
- Every stable GPU section slot receives at most 8 nearby light indices, selected by distance to the section AABB.
- Negative section coordinates and stable slot zero are supported.
- CPU tests cover emissive extraction, negative world coordinates, neighboring-section assignment and the 8-light local-list cap.
- GPU scene ABI adds fixed section-light counts, local index lists and point-light records after the stable voxel slots.
- Primary hits resolve their stable section slot and inspect only that slot's local list rather than scanning all scene lights.
- Local point lights use emission-derived range/intensity, distance attenuation and P6 secondary DDA visibility.
- Apple M4 runtime confirmed visible local lighting near emissive blocks with `lights=33`, `populatedLists=8/30`, `maxLightsPerSection=8/8` and no Vulkan/MoltenVK errors: **PASSED**.
- Initial world-entry lighting can lag section residency while Minecraft is still streaming chunks; the local light lists rebuild as scene revisions arrive.

## P8 - Emissive materials
Status: **IMPLEMENTED; Apple M4 runtime visual validation pending**

Implemented in `0.1.0-alpha.12`:
- `MaterialDefinition` now carries explicit emissive RGB in addition to `emissionLevel`.
- GPU material ABI expands from 32 to 48 bytes while preserving all previous field offsets and appending emissive RGB.
- GPU material packing/tests validate the new 48-byte record and RGB offsets.
- Minecraft material extraction assigns baseline vanilla emissive tints on the CPU side, keeping block identifiers out of the Vulkan shader.
- Baseline tint groups include soul fire, redstone torches, lava/magma, froglights, sea lantern/conduit, end rods, glowstone, shroomlight, and warm fire/torch/lantern sources.
- P7 local-light records expand to 8 words: position, radius, RGB and normalized intensity.
- CPU light-list tests verify emissive tint/intensity survives extraction and culling.
- GPU scene adds a fixed material-emission table so directly visible emissive surfaces can glow independently of Lambert lighting.
- `Emissive Materials` is the default validation mode; `Local Lights` remains available through F8 for A/B comparison.
- Colored local lights retain P7 distance attenuation and secondary-DDA hard occlusion.
- Java/client/tests/full CI development-JAR build: **PASSED**.

Runtime gate:
- Compare ordinary torch/lava lighting against soul-fire lighting; warm sources should be orange/yellow while soul sources should be cyan/blue.
- Confirm emissive blocks themselves remain visibly bright even when their surface normal is not facing the directional light.
- Confirm colored point-light tint reaches nearby surfaces and remains blocked by solid voxels.
- Confirm F8 can switch between `Local Lights` and `Emissive Materials` without geometry or lifecycle regressions.
- Confirm log contains `P8 emissive materials READY` with nonzero emissive/material light counts and no Vulkan/MoltenVK errors.

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
