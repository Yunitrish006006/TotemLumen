# Staged render pipeline migration

Status: implementation started on `refactor/staged-render-pipeline`. The existing full compute
renderer remains the correctness/reference baseline while stages are extracted incrementally.

## Architectural rule

Large rendering stages own independent pipeline identities and exchange data through explicit,
versioned intermediate-resource contracts. Shared tracing, intersection, material and sampling
helpers may remain reusable shader-library code, but new features must not extend the production
`P12FullBasePipeline` mega shader when they can consume an existing stage output.

This is deliberately not a micro-pass design. Voxel/model/fluid/entity nearest-hit logic remains a
shared scene/intersection backend. The separations are coarse compilation and ownership boundaries.

## Target stage graph

```text
Minecraft/native raster visibility
        |
        v
SURFACE_CAPTURE
        |
        v
MATERIAL_RESOLVE
   |        |        |
   v        v        v
DIRECT   INDIRECT  REFLECTION
LIGHT      GI
   \        |        /
        TEMPORAL
           |
        DENOISE
           |
        COMPOSITE
```

Each large stage must have:

- one explicit logical input/output contract;
- independent pipeline identity/cache behavior;
- independent readiness/failure state where practical;
- independent timing/profiling;
- resource ownership and retirement rules;
- a fallback policy that never presents stale cross-frame data.

## Phase 1 — contract foundation

Implemented first:

- `LumenRenderStage` names the coarse stages;
- `LumenStageResource` names stable logical resources;
- `StagedRenderPlan` validates producer/consumer ordering, duplicate stage identity and exclusive
  logical-output ownership;
- the current owned Raster path is represented as
  `SURFACE_CAPTURE -> INDIRECT_GI -> COMPOSITE`;
- `RasterSurfaceFrame` is the first concrete stage ABI. It carries the producing device, extent,
  frame serial, color semantic, base-colour view, R32 depth view and owned normal view.
- Raster lighting consumes this frame token rather than reaching back into Minecraft's target or
  capture implementation.
- color semantics are explicit: the first safe producer publishes `NATIVE_LIT_COLOR`; only a
  future material producer may publish `UNLIT_MATERIAL_COLOR` and enable independent-lighting
  composition.

The first owned Raster surface is intentionally conservative: completed native scene color is copied
to a Totem-owned texture, native depth is sampled into a Totem-owned R32_FLOAT texture, and a separate
SURFACE_CAPTURE pipeline reconstructs a full-resolution approximate normal field into RGBA16F. It is
not the final unlit material G-buffer and does not claim MATERIAL_RESOLVE completion.

## Phase 2 — owned raster surface capture

Initial implementation is now in the branch.

The unsafe post-world material replay has been removed from the active mixin chain. The old path
retained `ChunkSectionsToRender`/atlas/projection state and replayed native draw buffers after
Minecraft's pass closed; a resize run reproduced a closed slot-0 vertex buffer and render-pass
cleanup failure. Those replay classes/shaders are removed rather than left behind an opt-in flag.

The replacement `RasterSurfaceCapture` runs after native world drawing and owns every resource it
publishes:

- completed native scene color is copied into a Totem-owned texture;
- native depth is sampled once into a Totem-owned `R32_FLOAT` texture;
- surface normals are reconstructed once into a Totem-owned `RGBA16_FLOAT` texture;
- downstream lighting receives only `RasterSurfaceFrame`;
- resize/profile/world lifecycle retirement is owned by the capture stage;
- no chunk draw list, vertex/index buffer, lightmap, atlas or projection slice is retained.

This closes the borrowed-buffer architectural defect in code, but native lifecycle acceptance is
still required. It also deliberately does **not** solve independent material lighting: native color
is already visually lit, so the frame is tagged `NATIVE_LIT_COLOR` and cannot activate the
independent direct-RGB path.

The next surface/material increment must add stable material identity and unlit base data without
reintroducing native draw-buffer replay. Normal reconstruction is already owned by SURFACE_CAPTURE. Minecraft visibility and distant terrain remain
authoritative for the raster-primary path.

## Phase 3 — material resolve

The first MATERIAL_RESOLVE implementation is now present behind the development opt-in.

It owns a separate visible-surface identity pass plus material/P18 LUT uploads. The stage now also
publishes a full-resolution RGBA16F **base-property plane** containing:

- R = base roughness;
- G = base metallic;
- B = base opacity;
- A = normalized base emission level.

These values are decoded from the existing lossless 64-byte MaterialDefinition GPU ABI inside
MATERIAL_RESOLVE, so later stages do not need to repeat that base-property decode. Material-LUT
capacity changes and the independent P18 surface-set LUT now have separate resource lifetimes.

This remains **partial material coverage**, not completed independent material shading. The stage
now also uploads the existing P18 texture-scene ABI and publishes a full-resolution covered unlit
albedo plane for **static canonical textured cubes only**. It reconstructs the cube face from the
owned surface normal, evaluates the registered face UVs, samples the packed P18 albedo and multiplies
the captured biome/block tint. Alpha is an explicit coverage value; unknown surface sets, missing
texture handles and animated textures publish zero coverage so later stages must retain native
fallback.

Generic model meshes, fluids, entities, animated textured cubes, complete P18 normal/specular
overrides, AO and transmission/refraction surface data are not yet covered. Consequently
DIRECT_LIGHT remains disabled and the active safe renderer continues to composite INDIRECT_GI over
NATIVE_LIT_COLOR.

Changing material fidelity should not invalidate primary visibility or GI traversal pipelines unless
their explicit stage ABI changes.

## Phase 4 — split lighting

The first split is now implemented for the active safe Raster path:

- `RasterIndirectGiStage` owns the voxel atlas, `raster_indirect_gi` pipeline and
  reduced-resolution radiance target; it consumes the captured normal field rather than reconstructing
  primary normals itself;
- `RasterLightingFrame` is the frame-local INDIRECT_GI -> COMPOSITE ABI;
- `RasterCompositeStage` owns the presentation pipeline and is the only staged Raster pass that
  writes the main world color target;
- the old combined `raster_ray` shader has been removed. Its dormant direct-RGB/material branch,
  `LightSampler`, point-light visibility loop and emitter upload path are not part of the active
  shader compilation unit.

Remaining independent producers:

1. DIRECT_LIGHT: sun/moon/sky visibility and bounded RGB emitters, after MATERIAL_RESOLVE can provide
   unlit material input.
2. REFLECTION: optional specular/reflection transport.

All consume surface/material records and the shared ray-scene backend. Primary camera visibility must
not be retraced by these stages. Secondary visibility may call the shared scene tracer.

The existing `shared-filtered-trace` result remains a compiler-complexity reference: reducing one
large static trace call site materially reduced native reflection cold-pipeline creation despite only
a tiny SPIR-V size change. New stage work should therefore minimize duplicated large trace call sites
and long live-state ranges.

## Phase 5 — temporal, denoise and composite

Temporal history and denoising become independent consumers of lighting outputs rather than code
inside a geometry/traversal compilation unit. Final composite is the sole owner of writing the
presentation target.

## Legacy full renderer policy

`P12FullBasePipeline` remains selectable as the reference renderer until the staged path reaches
matched visual/lifecycle/performance acceptance. During migration:

- no new feature should enlarge it unless required for a correctness fix to the reference baseline;
- extracted stages get independent source hashes and pipeline-cache identities;
- stage ABI changes are versioned and tested;
- the reference path is used for image comparisons, not as the place to prototype new architecture.

## Validation order

For each extracted stage:

1. Java/unit/source contract checks.
2. Shader compile and descriptor/layout validation.
3. Native Vulkan lifecycle: resize, profile switch, world exit/re-entry, disconnect and shutdown.
4. Fixed-scene image comparison against the reference path for the stage's supported surface set.
5. Cold and warm pipeline-create timing.
6. Repeated frame-time/FPS windows with the same scene/settings.
7. Apple Silicon/MoltenVK validation before default adoption.

Compile speed alone never authorizes a stage migration if frame time or image correctness regresses.
