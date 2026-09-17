# P14E Exact Fluid Geometry — Alpha 43

## Status

- **P14E-A renderer-source fluid capture: IMPLEMENTED / CI PASS; Apple M4 runtime capture confirmed**
- **P14E-B bounded GPU fluid scene ABI + independent upload: IMPLEMENTED / CI PASS; first Apple M4 capacity blocker fixed, revalidation pending**
- **P14E-C shared nearest-hit integration: IMPLEMENTED / SHADER CI PASS; runtime visual validation pending**
- **P14E-D P15/P16 fluid optical semantics: IMPLEMENTED / SHADER CI PASS; runtime visual validation pending**

Alpha 43 has passed its implementation/build gate. It does **not** claim complete fluid rendering until the runtime gate below is validated in-world.

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
- resolved per-vertex UV coordinates;
- the emitted per-face ARGB color after Minecraft's cardinal face-lighting multiplication;
- the separate unlit `FluidModel`/world tint captured from `BlockTintSource.colorInWorld(...)` before face lighting;
- whether Minecraft emitted the face as double-sided;
- whether the cell is a pure `LiquidBlock` or fluid coexisting with block geometry (waterlogged/custom cell).

Separating unlit fluid tint from emitted face color is required for correct optics: using the already-lit face color for water transmission would multiply directional lighting into the optical tint a second time.

The `fluidOnlyCell` flag is also a correctness requirement: waterlogged stairs/fences can contain both block geometry and an exact fluid surface in the same voxel. Fluid presence must not erase the block geometry.

Absolute coordinates remain integer section origin + local float geometry, preserving far-world precision. Minecraft 26.2's `FluidRenderer` itself emits section-local coordinates using `pos & 15`, so P14E stores the renderer's native coordinate domain instead of converting through a large world-space float.

### Cache and invalidation

The capture cache is scoped to the active dimension and uses a concurrent block-position map because section tessellation may run off-thread. Revision + immutable scene snapshot observation is synchronized so the GPU uploader cannot pair a new revision with an older weakly-consistent map iteration.

The active dimension is established on client world join and rechecked during client ticks. Dimension change/disconnect/shutdown clears the cache.

A client block update invalidates a 3x3x3 neighborhood around the changed block. This is deliberately broader than the source block because fluid corner heights and visible side faces depend on neighboring fluid/block states. Minecraft's normal section rebuild later repopulates the exact resolved faces.

The Minecraft-facing ABI is checked in CI against the actual 26.2 client classes:

- `FluidRenderer.tesselate(BlockAndTintGetter, BlockPos, Output, BlockState, FluidState)`;
- `BlockTintSource.colorInWorld(BlockState, BlockAndTintGetter, BlockPos) -> int`;
- private `FluidRenderer.addFace(VertexConsumer, 20 floats, int color, int lightCoords, boolean addBackFace)`;
- `FluidRenderer.Output.getBuilder(ChunkSectionLayer) -> VertexConsumer`.

## P14E-B — bounded GPU scene ABI

P14E does not widen the existing 32-bit voxel record. Exact fluid geometry lives in a separate scene tail after the P14 model-mesh tail and P17 dynamic-entity tail.

Baseline capacity:

- 16,384 fluid-cell descriptors;
- 65,536 resolved fluid quads;
- 32,768 open-addressed block-coordinate lookup buckets.

Unlike P17's moving-entity section candidate lists, fluid geometry has a unique source block coordinate. The GPU scene therefore hashes exact `(blockX, blockY, blockZ)` directly to one fluid descriptor. A ray entering a voxel only tests the resolved quads owned by that fluid cell.

Fluid ABI v2 stores per descriptor:

- world block coordinates;
- fluid kind (`water`, `lava`, or other);
- stable fluid registry-id hash;
- first-quad offset and quad count;
- flags, including `FLUID_ONLY` for pure `LiquidBlock` cells;
- unlit fluid tint ARGB.

Each GPU quad stores four section-local positions, the emitted/lit ARGB face color and the captured double-sided flag. UVs remain in the immutable CPU snapshot for later P18 material/resource-pack integration rather than expanding the Alpha 43 tracing record.

`P14EFluidGpuUploader` now follows the same bounded scene window as the P5 voxel renderer: only fluid cells belonging to the ordered nearest/resident section set are eligible for the GPU fluid tail. The dimension-wide renderer capture cache may contain geometry from previously meshed sections, but that geometry is not packed merely because it still exists in the cache.

The resident selector preserves P5 section distance ordering. If a pathological resident window still exceeds the fixed cell/quad budget, the selector keeps nearer fluid geometry and drops farther cells with an explicit warning instead of throwing and disabling the entire renderer session. Internal lookup corruption/overflow remains a hard failure.

Fluid-only changes can repack/flush/copy the fluid tail without re-uploading static section voxels, P14 meshes, or P17 entity meshes. The uploader also compares the selected immutable fluid snapshot list, so revisions caused only by nonresident section meshing do not trigger redundant GPU uploads or temporal-history invalidation. An actual resident fluid-scene change conservatively invalidates temporal-history reads for that frame so flowing water cannot leave stale geometry trails.

Expected diagnostic:

```text
P14E fluid GPU scene: cells=<n>, quads=<n>, residentCells=<n>, residentQuads=<n>, droppedCells=<n>, droppedQuads=<n>, maxProbe=<n>, bytes=<n>, maxBytes=<n>
```

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

Shader transform ordering is explicitly preserved as:

```text
P12-P15/P13/P14 baseline
  -> P14E exact fluid geometry
  -> P17 dynamic entities (where applicable)
  -> P14E fluid optical rewrites
```

P14E optics intentionally runs after P17 because P17's P15 integration depends on the pre-optics P15 marker structure.

The tiny startup bootstrap intentionally remains fluid-free so Alpha 43 cannot reintroduce the Apple/MoltenVK cold-start regression fixed in Alpha 42.

Synthetic exact-fluid trace identities are:

```text
water = 0xFFFC
lava  = 0xFFFB
other = 0xFFFA
```

They are internal trace identities, not Minecraft material IDs.

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

The first Alpha 43 optical baseline is implemented:

- water uses the exact P14E surface as a transmissive P15 interface instead of an opaque synthetic material;
- water transmission tint uses the separately captured unlit Minecraft fluid tint, not the already-lit face color;
- the P15 water interface advances only past the exact triangle rather than jumping to the voxel exit, so waterlogged block geometry in the same cell remains discoverable;
- lava uses the exact P14E surface with an emissive/opaque baseline;
- P16 keeps the first raw exact-water hit as the reflection surface even when P15 transmission continues to the scene below it;
- water uses a low-roughness reflection baseline (`0.025`) on the exact captured triangle normal;
- P16 reflection and the transmitted base therefore coexist on the same water surface;
- resource-pack/LabPBR semantics remain P18 and must layer on top of, not replace, exact geometry.

Intentional Alpha 43 optical limitations:

- refraction is not implemented yet;
- volumetric/Beer-Lambert water absorption is not implemented yet;
- the baseline applies interface tint/attenuation per crossed water surface;
- arbitrary Fabric custom fluid render handlers that bypass vanilla `FluidRenderer.addFace(...)` are not yet claimed as supported.

## First Apple M4 runtime finding — 2026-09-18

The first Alpha 43 in-world run confirmed that the renderer-source capture and GPU scene path are active on Apple M4 / MoltenVK 1.4.2. The log showed resolved fluid capture beginning on a flowing-lava cell and the GPU fluid scene growing normally with bounded hash-probe diagnostics.

The run then exposed a P14E-B ownership bug: the uploader packed the entire active-dimension capture cache instead of only the P5 resident ray-scene window. As more chunk sections were tessellated, the cache reached `17,216` fluid cells and exceeded the fixed `16,384` GPU descriptor capacity, causing Totem Lumen to disable its renderer for the session while Minecraft itself continued running.

The fix keeps the dimension-wide capture cache as renderer-source data but filters GPU packing through the ordered resident section window already selected by P5. Capacity fallback is now nonfatal and nearest-first, and nonresident cache revisions no longer invalidate temporal history or cause redundant fluid-tail uploads.

Runtime revalidation must confirm that the new diagnostic stays bounded during exploration and that `droppedCells` normally remains `0` for the standard 64-section ray window.

## CI build gate

The accepted Alpha 43 head compiles/tests all Java scene/ABI code, verifies Minecraft 26.2 mixin descriptors, and shaderc-compiles all staged production variants at O0.

Latest verified shader results:

| Stage | GLSL chars | SPIR-V bytes | Result |
| --- | ---: | ---: | --- |
| Vulkan bootstrap readiness | 6,815 | 19,308 | PASS |
| P12-P15 + P14E full base | 78,028 | 194,676 | PASS |
| P14E + P17 enhanced base | 89,631 | 221,636 | PASS |
| P14E + P16 + P17 reflection | 70,282 | 172,564 | PASS |

All four compile with 0 shader errors; P14E geometry/optics markers and P17 integration markers pass. Bootstrap verification confirms `p14e=false` so exact-fluid work cannot block initial renderer readiness.

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
9. the GPU fluid scene reports bounded nonzero resident geometry with no fatal lookup/capacity failure and normally `droppedCells=0`;
10. camera/shadow/GI/P15/P16 all use the same exact fluid triangles;
11. water visibly transmits the scene behind it while retaining a P16 reflection on the exact surface;
12. lava remains emissive on its exact surface geometry;
13. the tiny bootstrap still reaches renderer readiness independently of the larger fluid-aware pipelines;
14. Alpha 42 dynamic-entity rendering remains functional.

## Follow-up order

```text
Alpha 42 / P17 Dynamic Entities
  -> Alpha 43 / P14E Exact Fluid Geometry
  -> P18 Resource Pack / LabPBR integration
```
