# P14D Block Entity Geometry

## Purpose

P14C made ordinary chunk/static `BlockStateModel` geometry generic by consuming Minecraft/Fabric's resolved emitted quads. That does not cover geometry drawn by a `BlockEntityRenderer`. The first visible runtime example was the bell: its static support geometry rendered through P14C, while the hanging bell body submitted by the block-entity renderer was absent from Totem Lumen rays.

P14D adds a distinct block-entity geometry domain without reverting to block-id shape tables or Bell-specific geometry.

## Source of truth

Minecraft 26.2 block-entity rendering submits renderer-resolved `Model` / `ModelPart` commands through the submit-node pipeline. Totem Lumen establishes a capture scope around `BlockEntityRenderDispatcher.submit(...)`, observes model submissions made inside that scope, and copies the resulting model-part vertex positions.

Minecraft model objects, render states, pose stacks and block entities remain render-thread-owned and are never stored as long-lived ray-tracing input. The P14D cache retains only Totem Lumen-owned primitive arrays and stable keys.

## Coordinate conversion

The dispatcher pose at the start of a block-entity submission is treated as the block-local origin. For each submitted model/model part, P14D computes:

```text
blockLocal = inverse(dispatcherBasePose) * submittedPose * modelPartPose * vertex
```

This strips camera/world placement while preserving renderer-selected orientation, animation pose and nested `ModelPart` transforms. The resulting vertices can use the same block-local `MODEL_MESH` traversal introduced by P14C.

## Static + renderer geometry composition

Block-entity renderer geometry is supplemental; it does not replace the owning block's ordinary block geometry.

When a P14D capture first appears, the owning section is queued through the existing priority dirty-section path. During that section extraction:

1. P14C resolves the ordinary block geometry.
2. P14D retrieves the exact P14C mesh payload when the base code is `MODEL_MESH`.
3. Compact primitive/full-cube cases use Minecraft's local outline-shape boxes as a conservative base representation.
4. The captured block-entity quads are appended.
5. The combined mesh receives one stable `MODEL_MESH` id in the voxel word.

This is required for cases such as a bell, where the static support and renderer-owned hanging body both need to participate in camera, shadow, GI, transmission and reflection rays.

## Stable mutable mesh IDs

Animation must not consume a new 12-bit mesh id every frame. P14D therefore extends `BlockModelMeshRegistry` with position-keyed dynamic mesh slots:

```text
(dimension, blockX, blockY, blockZ) -> stable mesh id
```

After the initial section switches its voxel geometry code to that id, later renderer poses replace the quad payload behind the same id. Identical poses do not advance the registry revision.

Dynamic ids are returned to a reusable pool when their chunk unloads or the client level changes. Static P14C ids remain immutable and deduplicated.

Build verification checks:

- static mesh dedup remains stable;
- the first dynamic registration allocates one independent id;
- an animation update keeps that id while replacing its vertices;
- an identical pose does not dirty the registry;
- snapshot quad-pool offsets remain contiguous even when ids have holes;
- a released dynamic id is reused before consuming additional 12-bit id space.

## GPU upload policy

P14C already appends one fixed-capacity model-mesh region to the shared scene SSBO. P14D reuses it; no descriptor binding or per-voxel ABI is added.

A registry revision can now dirty only the model tail. The mapped upload buffer repacks the current descriptors/quad pool, flushes that tail, and records an additional buffer copy before the existing compute dispatch. Section voxel data does not need to be repacked for every animation pose.

Consequently the same geometry is consumed by:

- primary camera rays;
- directional and local-light visibility;
- diffuse GI;
- environment/sky visibility;
- P15 filtered transmission when applicable;
- P16 reflection.

## Runtime gate

Alpha 39's first runtime gate is the Bell that exposed the P14C domain boundary.

Required evidence:

1. Alpha 38 persistent pipeline cache still produces fast base/P16 startup on a cache hit.
2. Log contains `P14D block-entity geometry capture active` for a block entity rendered through `Model` / `ModelPart` submission.
3. `P14 model GPU registry` reports `dynamicMeshes > 0`.
4. The previously missing hanging bell body appears in the Totem Lumen image.
5. Ringing the bell should update its captured pose without allocating a stream of new mesh ids. If the geometry appears but does not animate, the state/pose capture path remains incomplete and must be fixed before P14D is accepted.
6. Existing P14C static shapes, P12-P15 lighting and P16 reflection remain functional.
7. Chunk unload/reload and world exit/rejoin must not leave stale block-entity geometry at old positions.

Useful secondary tests after Bell:

- chest lid open/close;
- shulker-box open/close;
- other vanilla block-entity renderers that submit ordinary `Model` or `ModelPart` geometry.

These tests are runtime evidence, not implied by CI.

## Explicit limitations / follow-up domains

P14D does **not** claim all block-entity renderer output is now captured. The current generic hook covers geometry submitted as Minecraft `Model` / `ModelPart` commands. Renderer paths that submit other command families need explicit adapters, for example text, item/display commands, portals/beams, custom mesh callbacks or other specialized primitives.

Texture-alpha silhouette testing is still a material-domain follow-up. P14D currently copies geometric polygons, not per-texel alpha masks.

### Out-of-cell geometry

The existing P14 broad phase is voxel DDA: a `MODEL_MESH` is tested when the ray enters its owning block cell. A block-entity model whose rendered geometry protrudes materially outside that 1x1x1 cell can therefore be missed before the ray enters the owner cell. Correct arbitrary out-of-cell block-entity geometry requires an instance-bounds/broad-phase extension rather than silently widening every voxel test. This remains an explicit follow-up.

### Fluids

Flowing/sloped water and lava surfaces are produced by a separate fluid rendering domain. P14D does not treat those as block entities and does not change their geometry. Exact fluid-surface extraction remains the next pre-P17 geometry gate.

## Architectural invariant

Future block-entity support should extend renderer-command capture/adapters and the shared immutable mesh scene. Do not solve new block entities by adding a growing table of block IDs with hand-authored ray geometry unless the renderer genuinely exposes no reusable geometric source of truth.
