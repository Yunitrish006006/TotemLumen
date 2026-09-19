# Totem Lumen Roadmap

## P0 - Vulkan-only bootstrap
Status: **Apple Silicon runtime verified on Apple M4 / MoltenVK 1.4.2**; native Windows/Linux Vulkan validation still pending.

## P1 - Scene extraction
Status: **implementation complete and exercised in-world on Apple M4**; broader stress/runtime validation still pending.

Live invalidation follow-up (`0.1.0-alpha.14`):
- Minecraft 26.2 client block changes are captured from `ClientLevel.sendBlockUpdated(...)`, before the update is forwarded into the vanilla renderer.
- Dirty block updates schedule a high-priority section halo rebuild instead of waiting behind the initial chunk snapshot backlog.
- Up to 8 priority dirty sections are rebuilt per extraction while background chunk population remains throttled to 2 sections per extraction.
- Placed/broken/changed blocks and emissive-light changes now become visible without leaving and re-entering the world.
- Apple M4 runtime validation: **PASSED**.

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
- Apple M4 runtime confirmed visible local emissive lighting with nonzero resident emitters (`lights=33` observed): **PASSED**.

## P8 - Emissive materials
Status: **IMPLEMENTED; Apple M4 detailed tint validation still pending**

Implemented in `0.1.0-alpha.12`:
- `MaterialDefinition` now carries emissive RGB in addition to `emissionLevel`.
- GPU material ABI expanded from 32 bytes to 48 bytes with explicit emissive RGB offsets.
- Vanilla baseline emissive tints are assigned by the Minecraft material resolver rather than hard-coded in the shader.
- Local point-light records now carry RGB and intensity.
- Emissive surfaces add their own material emission independent of surface-facing direct light.
- Common baseline tints include warm fire/torch/lantern, cyan soul-fire family, orange-red lava, cool sea-lantern/conduit, and differentiated glowstone/froglight/shroomlight/end-rod classes.
- P7 section-local culling and P6 secondary-ray visibility remain unchanged.
- Scene invalidation in `0.1.0-alpha.14` now updates placed/broken emissive blocks without a world reload.
- Java/tests/CI development-JAR build: **PASSED**.

Runtime gate:
- Compare ordinary torch/lantern against soul torch/lantern and lava/sea lantern.
- Confirm emitted light carries visibly different tint.
- Confirm the emissive block surface remains bright even when its surface normal is not directly lit.
- Confirm F8 switching between `Emissive Materials` and `Local Lights` remains stable.
- Confirm no Vulkan/MoltenVK errors or significant new stalls.

## P9 - Soft shadow sampling
Status: **COMPLETE on Apple M4 / MoltenVK**

Validated in `0.1.0-alpha.15`:
- Adds a dedicated `Soft Shadow` F8 visualization mode while preserving `Hard Shadow` for A/B comparison.
- Uses four deterministic directional-light visibility rays per primary hit.
- A fixed low-discrepancy-style disk pattern is projected around the P6 directional-light vector with angular radius `0.055`.
- Sampling is deterministic per frame, so P9 does not introduce temporal shimmer before temporal reprojection exists.
- Surface-normal and light-direction offsets reuse the established P6 self-shadow bias.
- Visibility is averaged across valid samples to produce fractional penumbra values instead of binary visibility.
- Directly visible P8 emissive surfaces remain emissive in the soft-shadow validation mode.
- Apple M4 runtime confirmed the soft-shadow path is visually functional and stable: **PASSED**.

## P10 - Temporal reprojection / history validation
Status: **COMPLETE on Apple M4 / MoltenVK**

Validated in `0.1.0-alpha.16`:
- Adds a dedicated `Temporal History` F8 mode.
- Allocates two GPU-resident per-pixel history buffers inside the existing storage-buffer scene allocation; no temporal data is read back to CPU.
- History buffers ping-pong each completed frame so current compute writes never race the history buffer being sampled.
- Each history record stores accumulated RGBA plus hit voxel, raw face normal and material identity.
- Current world-space hit points are projected through the previous completed camera basis/FOV into previous-frame pixel coordinates.
- History is accepted only when the reprojected record still references the same voxel/material/normal; disocclusions and changed geometry fall back to the current sample immediately.
- Any selected-section/revision scene upload disables history reuse for that frame, so live block edits do not blend against stale geometry.
- Temporal mode rotates through one of the four P9 area-sun samples per frame and blends accepted history at weight `0.80`, allowing stationary pixels to converge over multiple frames while using one shadow ray per frame.
- The inter-frame Vulkan buffer barrier makes previous compute history writes visible to the next compute dispatch in addition to synchronizing the current upload transfer.
- Apple M4 runtime confirmed stationary convergence, stable camera motion and correct rejection after live geometry updates: **PASSED**.

## P11 - Spatial denoising
Status: **COMPLETE on Apple M4 / MoltenVK**

Validated in `0.1.0-alpha.17` / `0.1.0-alpha.18`:
- Adds a dedicated `Spatial Denoise` F8 mode.
- Reuses P10's immutable previous-frame history read buffer, so no extra storage allocation, CPU readback or additional Vulkan dispatch is required.
- Reprojection still performs the strict P10 center validation against the same voxel/material/normal before any spatial filtering is allowed.
- After a valid reprojection, a 3x3 neighborhood is gathered from the previous history buffer.
- Neighbor samples are accepted only when material ID and face normal match the current hit and each voxel-coordinate delta is at most one block.
- Center, axial and diagonal samples use progressively lower spatial weights; an additional voxel-distance weight suppresses cross-block smearing.
- The spatially filtered history is blended against the current rotating P9 sample with the existing P10 temporal history weight of `0.80`.
- `Temporal History` remains available for direct A/B comparison, and both direct-light temporal modes can share a valid history chain when switching between them.
- Scene changes still invalidate history reuse exactly as in P10, so placed/broken blocks cannot be blurred against stale geometry.
- Apple M4 runtime visual validation confirmed the spatial denoise path is usable: **PASSED**.

## P12 - 1-bounce diffuse GI
Status: **IMPLEMENTED / CI PASS; Apple M4 runtime visual validation pending**

Implemented for `0.1.0-alpha.19`:
- Adds one cosine-weighted diffuse hemisphere secondary ray per primary-hit pixel per submitted frame.
- The GI sample sequence uses pixel coordinates plus the low 8 bits of the frame index, providing changing samples across frames while P9 directional sampling continues to use its 4-sample mask.
- Secondary diffuse rays reuse the stable hashed voxel scene and 3D DDA path with a baseline GI distance cap of `48` blocks.
- A secondary-surface radiance estimate combines the hit material color, its emissive RGB, and visibility to the existing directional debug light.
- The one-bounce contribution is modulated by the primary material color with baseline strength `0.65`.
- Adds `Indirect GI` mode for viewing only the one-bounce term and `GI Composite` mode for direct + local-light + indirect output.
- `GI Composite` is the P12 validation default.
- P12 modes reuse P10 temporal reprojection and P11 edge-aware 3x3 spatial history filtering.
- History compatibility is now explicit: `Temporal History` / `Spatial Denoise` may share a direct-light history chain, while `Indirect GI` and `GI Composite` only reuse history from the same radiance mode. Switching between incompatible modes invalidates history immediately.
- Live scene changes still disable history reuse for the changed frame, preserving the P10 geometry-update rejection behavior.
- Java/tests/CI development-JAR build: **PASSED**.

Runtime gate:
- Use `Indirect GI` near a bright surface beside a darker perpendicular wall/floor and confirm visible one-bounce light appears where direct light is absent.
- Confirm occluded corners do not receive obviously impossible light and no bright streaks cross solid voxel boundaries.
- Hold the camera still and confirm GI noise converges over several frames rather than remaining fully random.
- Compare `Indirect GI` against `GI Composite` and confirm direct/local light is absent from the pure-indirect mode.
- Place/break blocks while in `GI Composite` and confirm stale indirect light is rejected rather than ghosting through changed geometry.
- Confirm log contains `P12 one-bounce GI READY` and no shaderc/Vulkan/MoltenVK errors.

Target for first meaningful public alpha.

## P13-P18 - Scene quality

P14E alpha-cutout status: **IMPLEMENTED / CI gate; runtime visual validation pending**. Static model quads retain sprite-local UVs and compact alpha masks so transparent texels do not block shared camera/shadow/GI/transmission/reflection rays.

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
