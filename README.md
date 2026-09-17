# Totem Lumen

Totem Lumen is a Minecraft 26.2 Fabric real-time lighting / voxel RT experiment built around Minecraft's Vulkan backend.

## Current development line

Current development order:

```text
Alpha 41 runtime readiness
  -> Alpha 42 / P17 Dynamic Entities
  -> Alpha 43 / P14E Exact Fluid Geometry
  -> P18 Resource Pack / LabPBR integration
```

Alpha 42 captures Player/general `LivingEntity` renderer geometry into a bounded dynamic scene, uploads it independently from static voxel data, and adds shared nearest-hit tracing for entity triangles.

### Alpha 42 Apple Silicon readiness rule

Dynamic-entity tracing is **not** allowed to block renderer startup. The P12-P15 base compute pipeline remains the readiness gate. P17 compiles independently in the background and is selected only after its complete Vulkan program is ready. A slow P17 MoltenVK/Metal compile therefore leaves the normal Totem Lumen base renderer active instead of keeping the client on vanilla-only rendering.

Expected startup sequence:

```text
Renderer state: WAITING_FOR_PIPELINE
Background Vulkan pipeline creation COMPLETE: shader=totem_lumen_p12_one_bounce_gi.comp
Renderer state: READY_FOR_SCENE_EXTRACTION
P5 stable GPU lookup READY: ...
P17 enhanced pipeline creation START: shader=totem_lumen_p17_dynamic_entities.comp ...
```

P17 may become available later:

```text
P17 enhanced pipeline creation COMPLETE: ...
P17 dynamic entity tracing READY: enhanced base pipeline selected for frame dispatch
```

See [`docs/P17_DYNAMIC_ENTITIES.md`](docs/P17_DYNAMIC_ENTITIES.md) for the Alpha 42 design/runtime gate and [`docs/ROADMAP.md`](docs/ROADMAP.md) for the broader development plan.

## Requirements

- Minecraft 26.2
- Fabric Loader 0.19.5 or compatible
- Fabric API 0.160.0+26.2
- Java 25
- Minecraft Vulkan graphics backend

Totem Lumen intentionally does not provide an OpenGL renderer fallback.
