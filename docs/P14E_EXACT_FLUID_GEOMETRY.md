# P14E Exact Fluid Geometry — Alpha 43

## Status

- **P14E-A renderer-source fluid capture: IMPLEMENTED / CI ABI PASS; runtime validation pending**
- **P14E-B bounded GPU fluid scene ABI + independent upload: IMPLEMENTED; current-head CI pending**
- **P14E-C shared nearest-hit integration: IMPLEMENTED; current-head shader CI/runtime validation pending**
- **P14E-D P15/P16 fluid optical semantics: NEXT**

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

## P14E-A — Minecraft 26.2 source of truth

Minecraft 26.2 section meshing walks fluid states separately from block models. `SectionCompiler` invokes `FluidRenderer.tesselate(...)`; the `FluidModel` selects the `ChunkSectionLayer`; `FluidRenderer` resolves the final visible faces and emits each quad through its private `addFace(...)` helper into `FluidRenderer.Output`.

P14E captures at that resolved face boundary:

```text
SectionCompiler
  -> FluidRenderer.tesselate(level, pos, output, blockState, fluidState)
       -> FluidRenderer.addFace(... four resolved vertex positions/UVs ...)
```

This is intentionally downstream of fluid height/neighbor/flow calculations. Totem Lumen does not duplicate Minecraft's slope and corner-height algorithm.

Minecraft section compilation can run on worker threads, so the capture scope is thread-local around one `FluidRenderer.tesselate(...)` call. No `FluidState`, `BlockState`, `BlockAndTintGetter`, `FluidRenderer`, `VertexConsumer`, `FluidModel`, or section-mesher object is retained after the call.

`FluidGeometrySnapshot` retains:

- active dimension id;
- source block coordinates;
- fluid registry id;
- integer section coordinates;
- section-local 4-vertex quad positions;
- resolved per-quad ARGB tint;
- resolved per-vertex UV coordinates;
- whether Minecraft emitted the face as double-sided;
- whether the cell is a pure `LiquidBlock` or fluid coexisting with block geometry (waterlogged/custom cell).

The last flag is a correctness requirement: waterlogged stairs/fences can contain both block geometry and an exact fluid surface in the same voxel. Fluid presence must not erase the block geometry.

Absolute coordinates remain integer section origin + local float geometry, preserving far-world precision.

### Cache and invalidation

The capture cache is scoped to the active dimension and uses a concurrent block-position map because section tessellation may run off-thread. A dimension change/disconnect/shutdown clears it.

A client block update invalidates a 3x3x3 neighborhood around the changed block. This is deliberately broader than the source block because fluid corner heights and visible side faces depend on neighboring fluid/block states. Minecraft's normal section rebuild later repopulates the exact resolved faces.

The Minecraft-facing ABI is checked in CI against the actual 26.2 client classes:

- `FluidRenderer.tesselate(BlockAndTintGetter, BlockPos, Output, BlockState, FluidState)`;
- private `FluidRenderer.addFace(VertexConsumer, 20 floats, int color, int lightCoords, boolean addBackFace)`;
- `FluidRenderer.Output.getBuilder(ChunkSectionLayer) -> VertexConsumer`.

## P14E-B — bounded GPU scene ABI

P14E does not widen the existing 32-bit voxel record. Exact fluid geometry lives in a separate scene tail after the P14 model-mesh tail and P17 dynamic-entity tail.

Baseline capacity:

- 16,384 fluid-cell descriptors;
- 65,536 resolved fluid quads;
- 32,768 open-addressed block-coordinate lookup buckets.

Unlike P17's moving-entity section candidate lists, fluid geometry has a unique source block coordinate. The GPU scene therefore hashes exact `(blockX, blockY, blockZ)` directly to one fluid descriptor. A ray entering a voxel only tests the resolved quads owned by that fluid cell.

Each descriptor stores:

- world block coordinates;
- fluid kind (`water`, `lava`, or other);
- stable fluid registry-id hash;
- first-quad offset and quad count;
- flags, including `FLUID_ONLY` for pure `LiquidBlock` cells.

Each GPU quad stores four section-local positions, the resolved ARGB tint and the captured double-sided flag. UVs remain in the immutable CPU snapshot for later P18 material/resource-pack integration rather than expanding the Alpha 43 tracing record.

`P14EFluidGpuUploader` tracks fluid-cache revision independently. Fluid-only changes can repack/flush/copy the fluid tail without re-uploading static section voxels, P14 meshes, or P17 entity meshes. A changed fluid scene conservatively invalidates temporal-history reads for that frame so flowing water cannot leave stale geometry trails.

Expected diagnostic:

```text
P14E fluid GPU scene: cells=<n>, quads=<n>, maxProbe=<n>, bytes=<n>, maxBytes=<n>
```

Capacity and lookup overflow are explicit failures; they must never silently overwrite unrelated scene data.

## P14E-C — shared nearest-hit tracing

`P14EFluidShaderPatch` extends the existing shared voxel DDA instead of creating a separate water renderer.

For every traversed voxel it:

1. performs the block-coordinate fluid lookup;
2. reconstructs captured vertices from integer section origin + section-local floats;
3. intersects each resolved quad as two triangles;
4. for a pure `LiquidBlock`, suppresses the old full-cube static voxel proxy;
5. for a waterlogged/custom coexistence cell, also evaluates the normal P14 block geometry;
6. returns whichever exact fluid or static hit is nearest inside that voxel.

This preserves waterlogged geometry while preventing ordinary water/lava voxels from appearing as full opaque cubes.

The same fluid-aware `traceRayLimited(...)` is compiled into:

- full P12-P15 base rendering;
- P17 enhanced dynamic-entity rendering;
- split P16 reflection.

The tiny startup bootstrap intentionally remains fluid-free so Alpha 43 cannot reintroduce the Apple/MoltenVK cold-start regression fixed in Alpha 42.

Synthetic trace identities are reserved for the first geometry gate:

```text
water = 0xFFFC
lava  = 0xFFFB
other = 0xFFFA
```

They do not claim final material semantics. P14E-D replaces the temporary opaque/material baseline with water transmission and lava emission rules.

## Shared tracing invariant

There is one exact fluid geometry truth for:

- camera/primary rays;
- sun and moon visibility;
- local-light shadows;
- diffuse GI;
- environment/sky visibility;
- P15 transmission;
- P16 reflection.

No reflection-only water plane, shadow-only fluid proxy, or shader-side fluid-level approximation is acceptable.

## P14E-D — optical semantics

Next implementation gate:

- water uses the exact P14E surface as a transmissive interface rather than an opaque synthetic material;
- lava uses the exact P14E surface with emissive/opaque baseline semantics;
- P15 layered transmission understands the water trace identity instead of resolving it through static voxel material lookup;
- P16 reflection uses the exact sloped triangle normal already produced by P14E-C;
- underwater rays cross the same captured surface used by above-water camera rays;
- resource-pack/LabPBR semantics remain P18 and must layer on top of, not replace, exact geometry.

## Runtime gate

Alpha 43 is accepted only when all of the following are demonstrated in-world:

1. still/source water produces captured top and required side faces;
2. fluid levels 1–7 produce distinct heights;
3. a sloped/flowing surface preserves Minecraft's corner heights;
4. waterfalls/lavafalls produce vertical side geometry;
5. water/lava crossing a section boundary has no seam caused by Totem geometry ownership;
6. waterlogged block geometry remains present while its fluid surface is traced;
7. placing/removing water and subsequent flow changes replace stale captured faces without rejoining the world;
8. underwater camera transitions do not leave stale or duplicate surfaces;
9. the GPU fluid scene reports bounded nonzero geometry with no unexpected lookup/capacity failure;
10. camera/shadow/GI/P15/P16 all use the same exact fluid triangles;
11. the tiny bootstrap still reaches renderer readiness independently of the larger fluid-aware pipelines;
12. Alpha 42 dynamic-entity rendering remains functional.

## Follow-up order

```text
Alpha 42 / P17 Dynamic Entities
  -> Alpha 43 / P14E Exact Fluid Geometry
  -> P18 Resource Pack / LabPBR integration
```
