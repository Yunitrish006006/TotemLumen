# Totem Lumen Roadmap

## P0 - Vulkan-only bootstrap

Status: **compile-verified**; native Vulkan and Apple Silicon runtime validation pending.

## P1 - Scene extraction

Status: **implementation complete**; runtime validation pending.

Implemented:
- Client level/chunk lifecycle tracking.
- Renderer-relevant `LevelExtractor.blockChanged` hook.
- Immutable scene update queue.
- Dirty-section tracking with section-boundary halo coverage.
- Extraction-time camera frame snapshot.

## P2 - Materials and CPU scene

Status: **implementation complete; runtime validation pending**

Implemented:
- Minecraft-independent material flags/definitions and stable integer material IDs.
- Air is permanently material ID 0.
- 16x16x16 section voxel snapshots using `(y << 8) | (z << 4) | x` indexing.
- Initial `BlockState -> MaterialDefinition` adapter.
- Non-empty sections are scheduled when chunks load.
- Block updates schedule the same neighbouring section halo used by the renderer dirty path.
- Section rebuild work is capped at 2 sections per extraction frame to avoid a join-world frame spike.
- Only already-loaded chunks are read (`ChunkStatus.FULL`, `loadOrGenerate=false`).
- Rebuilt sections are copied into Minecraft-free `SectionSnapshot` objects before entering `RayScene`.
- All-air rebuilds explicitly remove previous CPU section data.
- Chunk unload and dimension changes remove stale snapshots/backlog.
- Material/section coordinate and scene ownership behavior is unit tested.

Runtime checks required:
- Join a world and confirm snapshot backlog drains without a large main-thread spike.
- Verify stone, glass, torch, glowstone, water, lava and cutout vegetation classification.
- Place/break blocks and verify only affected sections rebuild.
- Teleport / switch dimensions and verify stale snapshots disappear.
- Run the same checks on native Vulkan and Apple Silicon/MoltenVK.

Exit criteria: the visible loaded world has a stable Totem Lumen-owned CPU voxel/material representation that updates incrementally.

## P3 - Vulkan GPU scene

Next phase after P2 runtime smoke validation:
- Establish a narrow bridge to Minecraft's Vulkan backend only where Blaze3D public API is insufficient.
- Device-local voxel/material/light buffers.
- Staging/ring upload path.
- Incremental section uploads keyed by `SectionKey` + revision.
- Synchronization/lifetime ownership rules.
- GPU timestamp/debug-label infrastructure.
- No OpenGL path.

Exit criteria: a 3x3-chunk scene can be uploaded and updated without full-scene reupload or Vulkan validation errors.

## P4 - Vulkan compute voxel traversal

- Compute pipeline.
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
