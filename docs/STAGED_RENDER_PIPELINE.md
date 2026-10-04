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
- the current Raster material-lighting preview is represented as
  `SURFACE_CAPTURE -> DIRECT_LIGHT -> COMPOSITE`;
- `RasterSurfaceFrame` is the first concrete stage ABI. It carries the producing device, extent,
  frame serial, base-colour view and depth view. It is borrowed for one matching frame only.
- Raster lighting consumes this frame token rather than depending on a pair of capture-owned views
  with no explicit generation identity.

The Raster surface currently contains opaque/cutout base colour with baked vertex shade plus depth.
It is not yet the final unlit material G-buffer and does not claim MATERIAL_RESOLVE completion.

## Phase 2 — owned raster surface capture

Current blocking defect: the experimental material capture replays Minecraft chunk draws after the
native world pass and borrows native draw buffers. A resize/lifecycle run reproduced a closed
slot-0 vertex buffer followed by an unbalanced render-pass failure.

Do not add more lighting stages on top of this borrowed-draw implementation. Replace replay with an
owned surface-capture output created at a lifetime-safe point. Acceptance requires resize,
profile-switch, disconnect/rejoin and normal shutdown without borrowed-buffer access or open render
passes after failure.

The owned output should initially provide:

- depth;
- geometric/shading normal;
- stable material identity;
- unlit/tinted base colour;
- surface flags needed for cutout/opaque classification.

Minecraft visibility and distant terrain remain authoritative for the raster-primary path.

## Phase 3 — material resolve

Create an independent MATERIAL_RESOLVE pipeline/resource ABI. Move P18-facing decode work that can
operate from captured material identity into this stage:

- albedo/tint;
- roughness;
- metallic;
- emission;
- AO;
- transmission flags/parameters where available.

Changing material fidelity must not invalidate primary visibility or GI traversal pipelines unless
their input ABI changes.

## Phase 4 — split lighting

Create independent producers:

1. DIRECT_LIGHT: sun/moon/sky visibility and bounded RGB emitters.
2. INDIRECT_GI: secondary diffuse transport only.
3. REFLECTION: optional specular/reflection transport.

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
