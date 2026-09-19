# P14C Generic Block Models

Status: **Alpha 37 implementation gate before P17**

## Purpose

P14A/P14B proved block-local geometry with compact special cases, but the old resolver was not a complete Minecraft model renderer. It understood slabs, stairs, fences, walls, panes/bars, doors, trapdoors and fence gates; every other ordinary block state could fall back to a full cube even when Minecraft rendered a rail, torch, ladder, button, lever, hopper, cauldron, plant cross-plane or another non-cube model.

P14C removes block-id shape guessing as the general solution. For static/chunk block rendering, Minecraft/Fabric's resolved `BlockStateModel` is now the geometry source of truth.

## Geometry domains

"All Minecraft models" is not one API. Totem Lumen treats the renderer as several geometry domains so future work cannot silently regress to cubes.

| Geometry domain | Alpha 37 status | Source of truth | Notes |
| --- | --- | --- | --- |
| Ordinary static/chunk block-state models | **Generic path implemented** | `BlockStateModelSet` + `FabricBlockStateModel.emitQuads(...)` | Actual emitted quad positions are copied into Totem Lumen-owned meshes. |
| Canonical full cubes | **Fast path retained** | emitted model recognized as unit cube | Uses existing `SURFACE_CUBE`; no generic triangle loop. |
| Clear/stained glass and panes | **Dedicated optical path retained** | P15 geometry metadata | Keeps colored transmission semantics instead of reducing glass to opaque triangles. |
| Existing P14 compact shapes | **Compatible, no longer required as global fallback** | prior P14 codes | Remain ABI-compatible and useful for old/resident data; newly extracted ordinary models can use generic mesh geometry. |
| Special/block-entity renderers | **Not equivalent to static BlockStateModel** | block-entity/special renderer extraction | Examples include renderer-owned or animated chest/banner/skull/shulker-like geometry. Alpha 37 only uses outline-shape fallback when no static model quads exist. This domain must be captured separately. |
| Fluids | **Dedicated domain pending** | Minecraft fluid renderer | Alpha 37 preserves the current conservative fluid volume; exact sloped/flowing surfaces are not claimed. |
| Alpha-cutout silhouette | **P14E implemented; runtime validation pending** | emitted quad UV + sprite alpha | Transparent texels reject generic-mesh hits through the shared ray path; see `P14E_ALPHA_CUTOUT.md`. |
| Per-position/random model offsets | **Pending** | block model offset/state | Mesh extraction currently stores block-local vertices. Position-specific visual offsets need an instance transform strategy so mesh dedup remains effective. |
| Geometry outside the owning voxel | **Conservative limit** | model/resource pack | P14C still uses voxel DDA as broad phase and clips generic triangle hits to that voxel's entry/exit interval. Exotic models extending outside their cell need a broader instance bound. |
| Dynamic entities | **P17** | immutable entity snapshots | Player/mob/item/entity geometry is deliberately separate from block-state model extraction. |

The project must not mark a pending domain as complete merely because an outline/full-cube fallback renders something.

## Static block-model extraction

During the existing section snapshot boundary, P14C resolves the block state's current Minecraft model and asks Fabric's renderer API to emit every quad with face culling disabled.

```text
mutable Minecraft level/block state/model
    -> FabricBlockStateModel.emitQuads(...)
    -> copy four geometric vertex positions per quad
    -> deduplicate immutable Totem Lumen mesh
    -> 12-bit mesh id in voxel geometry word
    -> discard Minecraft model/quad references
```

No Minecraft `BlockStateModel`, `MutableQuadView`, level object or block state is retained as long-lived GPU input.

A resource reload that swaps `BlockStateModelSet` schedules populated sections through the established bounded background snapshot queue. Existing mesh ids remain valid until resident sections are re-extracted, avoiding a synchronous world-wide rebuild or dangling mesh indices.

## Voxel ABI

P14C reserves one existing 16-bit geometry family:

```text
0xA000 = MODEL_MESH
low 12 bits = mesh id 0..4095
```

Mesh id `0` means intentionally empty static geometry. This is important for invisible/special-render blocks: they must not become fake full cubes merely because their chunk model emits no quads.

The voxel remains 32 bits:

```text
low 16 bits  = material id
high 16 bits = geometry code / MODEL_MESH id
```

## GPU mesh registry

The generic mesh registry is appended after the established P5/P12 history + pixel layout inside the same scene SSBO. Existing offsets therefore remain stable.

Capacity baseline:

| Item | Alpha 37 cap |
| --- | ---: |
| Generic mesh ids | 4095 non-zero ids |
| Quads per mesh | 512 |
| Total generic quads | 65,536 |
| Descriptor words | 8,192 |
| Vertex words per quad | 12 float words |
| Reserved scene-tail capacity | about 3.03 MiB |

The full capacity is reserved in the Vulkan allocation, but only the descriptor table plus currently used quad words are flushed/copied on a full scene upload. Camera-only frames do not re-upload the model pool.

Meshes are content-deduplicated by copied local-space vertex positions. A canonical six-face unit cube bypasses the mesh registry and keeps the existing full-cube fast path.

## Ray traversal

Voxel DDA remains the broad phase. For a `MODEL_MESH` candidate:

1. read its mesh descriptor;
2. iterate its bounded quad range;
3. split each quad into two two-sided triangles;
4. run triangle intersection only inside the current DDA cell interval;
5. return the closest geometric triangle normal.

The same transformed P14 trace helpers are consumed by:

- primary camera rays;
- directional/local-light visibility;
- P12 diffuse GI bounce rays;
- P13 sky/environment visibility;
- P15 filtered transmission traversal;
- P16 reflection rays.

There is no separate "reflection geometry" or "shadow geometry" approximation.

## Performance policy

P14C prioritizes correctness and future compatibility while preserving the high-frequency full-cube fast path. Generic triangle loops run only after DDA identifies a model-mesh voxel candidate.

Do not optimize by restoring block-id shape tables. If profiling shows a hot model family, acceptable follow-ups include mesh bounds, BVH/cluster acceleration, compact quantized vertices, or promoted fast paths that preserve the same geometry source of truth.

Runtime cost must be measured on Apple/MoltenVK before changing caps or approximation policy.

## Validation matrix

Alpha 37 runtime validation should include, at minimum:

- rails including ascending rails;
- torch and wall torch;
- lantern, chain and end rod;
- ladder;
- buttons, levers and pressure plates;
- hopper, cauldron and anvil;
- flowers, crops and grass/cross-plane models;
- slabs, stairs, fences, walls, panes, doors, trapdoors and fence gates as regressions;
- clear/stained glass transmission as a P15 regression;
- shadows/GI/reflection striking non-cube generic geometry;
- live block-state changes and resource reload refresh;
- no shaderc/Vulkan/MoltenVK errors.

Separate later gates must validate block-entity/special-render geometry, exact fluid surfaces, alpha cutout and dynamic entities before Totem Lumen claims total Minecraft renderer geometry coverage.
