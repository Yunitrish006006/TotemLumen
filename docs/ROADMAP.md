# Totem Lumen Roadmap

## P0 - Vulkan-only bootstrap

Status: **in progress**

- Fabric 26.2 / Java 25 project.
- Client-only mod metadata.
- Development launch requests Vulkan.
- Runtime device/backend probe.
- Explicit refusal to implement an OpenGL fallback.
- Apple Silicon platform detection for diagnostics only.
- CI compilation on Java 25.

Exit criteria:

- `gradle build` passes.
- Vulkan development client reaches the title screen/world with Totem Lumen loaded.
- Logs identify the active backend and GPU.
- OpenGL startup leaves Totem Lumen disabled instead of calling Vulkan-specific code.

## P1 - Scene extraction

- Track world attach/detach and dimension changes.
- Track chunk/section load and unload.
- Track block updates.
- Define immutable `SceneUpdate` events.
- Keep Minecraft mutable world state out of the renderer hot path.

Exit criteria: a debug counter accurately reports tracked chunks/sections and changes after block placement/destruction.

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
