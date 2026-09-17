# P14E Exact Fluid Geometry — Alpha 43

## Status

- **P14E-A renderer-source fluid capture: ACTIVE**
- **P14E-B bounded GPU fluid scene ABI: PENDING**
- **P14E-C shared nearest-hit integration: PENDING**
- **P14E-D P15/P16 fluid optical semantics: PENDING**

## Goal

Alpha 43 makes water and lava surfaces first-class geometry in Totem Lumen's shared ray-traced scene. The geometry source of truth is Minecraft 26.2's resolved fluid tessellation, not hand-authored fluid-level shape tables.

Required baseline coverage:

- source/still water and lava;
- levels 1–7;
- sloped top surfaces with independently resolved corner heights;
- flowing water/lava;
- vertical waterfall/lavafall side faces;
- neighbor-dependent faces and section-boundary surfaces;
- live placement/removal/flow updates;
- underwater camera entry/exit without stale surface geometry.

## Minecraft 26.2 source of truth

Minecraft 26.2 section meshing walks fluid states separately from block models. `SectionCompiler` invokes `FluidRenderer.tesselate(...)`; the `FluidModel` selects the `ChunkSectionLayer`; `FluidRenderer` resolves the final visible faces and emits each quad through its private `addFace(...)` helper into `FluidRenderer.Output`.

P14E captures at that resolved face boundary:

```text
SectionCompiler
  -> FluidRenderer.tesselate(level, pos, output, blockState, fluidState)
       -> FluidRenderer.addFace(... four resolved vertex positions/UVs ...)
```

This is intentionally downstream of fluid height/neighbor/flow calculations. Totem Lumen must not duplicate Minecraft's slope and corner-height algorithm.

## Extraction boundary

Minecraft section compilation can run on worker threads. P14E therefore uses a thread-local capture scope around one `FluidRenderer.tesselate(...)` call and copies the final face data into immutable Totem-owned records.

No `FluidState`, `BlockState`, `BlockAndTintGetter`, `FluidRenderer`, `VertexConsumer`, `FluidModel`, or section-mesher object is retained after the call.

The immutable common/client boundary is `FluidGeometrySnapshot`:

- active dimension id;
- source block coordinates;
- fluid registry id;
- section coordinates;
- section-local quad vertex positions;
- resolved per-quad ARGB tint;
- resolved per-vertex UV coordinates;
- whether Minecraft emitted the face as double-sided.

Absolute coordinates stay split into integer section origin plus local float geometry so far-world precision is not collapsed to one large float.

## Cache and invalidation

The client cache is scoped to the currently active dimension. A dimension change clears the cache before accepting new tessellation results.

A client block update invalidates a 3x3x3 neighborhood around the changed block. This is deliberately broader than the source block because Minecraft fluid corner heights and visible side faces depend on neighboring fluid/block states. The normal section rebuild later repopulates the exact resolved faces from `FluidRenderer`.

P14E-A does not yet upload fluid meshes to Vulkan. Its purpose is to prove that exact renderer-resolved geometry can be captured safely, deterministically, and without retaining Minecraft render objects.

## GPU ABI plan

P14E-B must not widen the existing 32-bit voxel record. Fluid geometry gets a separate bounded scene tail or a compatible shared mesh domain.

The baseline GPU representation should preserve:

- section origin;
- fluid type/material identity;
- quad range;
- local AABB;
- quad vertex positions;
- enough optical identity for P15/P16 to distinguish water/lava and future resource-pack material overrides.

Capacity overflow must be explicit and diagnostic; it must never silently overwrite unrelated geometry.

## Shared tracing invariant

Once P14E-C is enabled, there is one fluid geometry truth for:

- camera/primary rays;
- sun and moon visibility;
- local-light shadows;
- diffuse GI;
- environment/sky visibility;
- P15 transmission/refraction baseline;
- P16 reflection.

No reflection-only water plane, shadow-only fluid proxy, or shader-side level-table approximation is acceptable.

## P15/P16 optical baseline

P14E-D will define the first optical behavior after geometry is proven:

- water is transmissive rather than opaque;
- lava remains emissive/opaque unless later material policy says otherwise;
- reflection uses the same exact sloped surface normal derived from captured triangles;
- underwater rays cross the same surface used by above-water camera rays;
- resource-pack/LabPBR semantics remain P18 and must layer on top of, not replace, exact geometry.

## Runtime gate

Alpha 43 is accepted only when the following are demonstrated in-world:

1. still/source water produces captured top and required side faces;
2. fluid levels 1–7 produce distinct heights;
3. a sloped/flowing surface preserves Minecraft's corner heights;
4. waterfalls/lavafalls produce vertical side geometry;
5. water/lava crossing a section boundary has no seam caused by Totem geometry ownership;
6. placing/removing water and subsequent flow changes replace stale captured faces without rejoining the world;
7. underwater camera transitions do not leave stale or duplicate surfaces;
8. the GPU fluid scene reports bounded nonzero geometry with no unexpected overflow;
9. camera/shadow/GI/transmission/reflection all hit the same fluid geometry;
10. Alpha 42 staged startup and dynamic-entity rendering remain functional.

## Follow-up order

```text
Alpha 42 / P17 Dynamic Entities
  -> Alpha 43 / P14E Exact Fluid Geometry
  -> P18 Resource Pack / LabPBR integration
```
