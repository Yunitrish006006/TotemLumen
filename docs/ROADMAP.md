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
- Latest Apple M4 validation: voxel `(-464, 22, 47)`, material `24`, normal `(0, 1, 0)`, distance `0.5`, steps `1`: **PASSED**.
- Camera-center DDA diagnostic path is operational; a MISS is valid when looking at sky or outside the temporary 64-section debug upload.

Remaining architecture improvement (not a P4 blocker):
- Replace temporary linear 64-section lookup with the permanent coordinate -> stable slot lookup before production-scale rendering.

## P5 - Debug visualization
Status: **P5A/P5B runtime-verified on Apple M4; real-world DDA composite gate implemented and awaiting visual validation**

P5A - full-frame DDA debug grid:
- 160-pixel-wide low-resolution target preserving the current window aspect ratio.
- One Vulkan compute invocation per debug pixel.
- Full camera ray generation from extracted camera quaternion/FOV.
- Debug shader modes implemented: normal, material ID, distance, traversal-step heatmap.
- Current validation mode: normal.
- One-time CPU readback is used only as a correctness gate; production hot path will remain GPU-only.
- Apple M4 runtime result: `160x90`, `14102` hits, `298` misses, center RGBA `0xffff8080`: **PASSED**.

P5B - texture/composite gate:
- Vulkan compute writes an RGBA debug buffer.
- `vkCmdCopyBufferToImage` copies directly into a Minecraft-owned `GpuTexture` backed by `VulkanGpuTexture`.
- Texture remains in the Minecraft Vulkan device / submission timeline; no second device, surface, or swapchain.
- `GpuTextureView` is displayed through Fabric 26.2 `HudElementRegistry` / `GuiGraphicsExtractor.blit`.
- Apple M4 runtime result: `P5 debug composite READY`: **PASSED**.

P5C - real-world DDA image composite:
- Implemented a one-shot real Minecraft voxel normal-debug render.
- Uses the same extracted camera/section model as P5A.
- GPU DDA output stays on GPU and is copied directly into the Minecraft-owned debug texture.
- HUD automatically falls back to the deterministic gradient until the real DDA texture is ready, then replaces it.
- This gate is intentionally one-shot; after visual correctness is confirmed it will become a persistent per-frame renderer.

Next after P5C visual validation:
- Make the debug frame update continuously with camera/scene changes.
- Add runtime mode switching for Normal / Material / Distance / Steps.
- Remove the temporary deterministic gradient fallback from normal operation.
- Replace the temporary 64-section linear lookup with the permanent GPU scene/slot lookup.

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
