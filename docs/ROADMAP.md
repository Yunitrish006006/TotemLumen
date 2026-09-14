# Totem Lumen Roadmap

## P0 - Vulkan-only bootstrap

Status: **compile-verified**

- Fabric 26.2 / Java 25 project.
- Client-only mod metadata.
- Development launch requests Vulkan.
- Runtime device/backend probe.
- Explicit refusal to implement an OpenGL fallback.
- Apple Silicon platform detection for diagnostics only.
- CI compilation on Java 25.

Remaining runtime validation:

- Launch the development client on at least one native Vulkan machine and one Apple Silicon Mac.
- Confirm logs identify backend/GPU correctly on both paths.

## P1 - Scene extraction

Status: **implementation complete; runtime validation pending**

Implemented:

- Client level attach/change tracking.
- Client chunk load/unload tracking.
- 26.2 `LevelExtractor.blockChanged` hook for renderer-relevant block changes.
- Bounded thread-safe `SceneUpdateQueue`.
- Immutable Minecraft-free scene update records.
- Minimal CPU `RayScene` chunk index.
- Dirty section deduplication mirroring the 3x3x3 block halo / section-boundary coverage used by the 26.2 extractor.
- Extraction-time immutable camera/frame snapshot: position, quaternion rotation, FOV, dimension, detached-camera flag.
- Unit tests for dimension reset, ordering, negative coordinates, boundary halos, and unload cleanup.

Runtime checks still required to close P1:

- Enter a real world with Vulkan enabled and verify chunk counts settle correctly.
- Place/break blocks, trigger piston/explosion updates, and verify dirty-section counters move.
- Teleport and switch dimensions; verify stale scene/frame data is cleared.
- Validate the same extraction path on one native Vulkan machine and one Apple Silicon/MoltenVK machine.

Exit criteria: runtime diagnostics accurately report tracked chunks/sections and camera state across normal play, block updates, teleportation, and dimension changes.

## P2 - Materials and CPU scene

- `BlockState -> MaterialId` registry.
- Minimum material classes: air, opaque, emissive, cutout, translucent.
- Compact `ChunkSectionScene` storage.
- Dirty geometry vs dirty lighting flags.

Exit criteria: stone, glass, torch, glowstone, water, and air map deterministically to distinct material classes.

## P3 - Vulkan GPU scene

- Establish the narrow Minecraft Vulkan backend bridge if required.
- Device-local voxel/material/light buffers.
- Staging/ring upload path.
- Dirty-section incremental uploads.
- Synchronization/lifetime ownership rules.
- Timestamp/debug-label infrastructure.

Exit criteria: a 3x3-chunk scene can be uploaded and updated without full-scene reupload or validation errors.

## P4 - Vulkan compute voxel traversal

- Compute pipeline.
- 3D DDA traversal.
- Chunk/section empty-space skipping.
- Debug output: hit distance, normal, material ID, voxel position, traversal step count.

Exit criteria: center-screen debug rays match Minecraft blocks across chunk boundaries, negative coordinates, and world height edges.

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
