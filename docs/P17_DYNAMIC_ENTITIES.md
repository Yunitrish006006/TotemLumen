# P17 Dynamic Entities — Alpha 42

## Status

- **P17A capture + CPU broad phase: IMPLEMENTED / CI PASS**
- **P17B bounded Vulkan scene ABI + independent tail upload: IMPLEMENTED / CI PASS**
- **P17C shared nearest-hit shader integration: IN PROGRESS**
- **P17D material fidelity: PENDING**

Alpha 42 does not yet claim visually complete entity ray tracing until P17C is integrated and the runtime gate passes.

## Goal

Alpha 42 makes players and ordinary living entities first-class geometry in Totem Lumen's ray-traced scene instead of leaving them as a vanilla-only layer over a ray-traced world.

The first accepted domain is:

- local/remote players;
- ordinary `LivingEntity` renderers that submit Minecraft `Model` geometry;
- renderer/model animation and pose changes captured every visible frame.

The implementation must be generic. Do not add player-, zombie-, cow-, or other mob-specific ray geometry tables.

## Source of truth

Minecraft 26.2 entity rendering is submission based. `EntityRenderDispatcher.submit(...)` scopes one entity renderer call, while living-entity bodies and many render layers ultimately submit `Model` geometry through `SubmitNodeCollector.submitModel(...)` / ordered submit collections.

P17 therefore reuses the same architectural principle as P14D:

1. bind Minecraft's temporary render state to the stable source entity identity during `EntityRenderer.createRenderState(...)`;
2. establish a capture scope around one entity submission;
3. observe renderer-resolved `Model` commands already selected by Minecraft;
4. apply the exact render state to the model before copying geometry;
5. copy only transformed primitive positions into Totem Lumen-owned arrays;
6. retain no live Minecraft `Entity`, `Model`, `ModelPart`, render-state, or `PoseStack` objects in the ray-tracing scene.

Alpha 42 initially captures only `LivingEntityRenderState` scopes. Other entity command families are explicit follow-ups.

## Coordinate model

Entity render submission receives a camera-relative placement `(renderX, renderY, renderZ)` plus a `PoseStack`. Totem Lumen captures the dispatcher entry pose and removes only the dispatcher placement from submitted model vertices.

The retained geometry is therefore entity-local but already includes renderer-selected orientation, animation, baby/adult scaling, sleeping/swimming/crouching transforms, model-part transforms, and other pose changes applied by the renderer.

The immutable snapshot separately stores the entity render state's absolute world position. World-space bounds are derived as:

```text
worldBound = entityWorldPosition + capturedEntityLocalVertex
```

Absolute world positions and AABBs remain double precision on the CPU. The P17B GPU ABI converts them to integer section origins plus section-local float coordinates, avoiding premature far-world precision loss.

## Immutable snapshot

`DynamicEntitySnapshot` is the common/client boundary. It contains:

- Totem Lumen instance id;
- dimension id;
- entity type id;
- absolute world position;
- world-space AABB;
- copied entity-local quad positions.

The constructor defensively copies geometry and rejects malformed/non-finite data. No Minecraft client classes appear in this common scene type.

## Broad phase

Voxel DDA cannot discover arbitrary moving entity meshes through an owner voxel the way P14C/P14D do. P17 therefore adds a dynamic broad phase before the GPU trace path is enabled.

The Alpha 42 baseline bins every dynamic entity AABB into each overlapping 16x16x16 `SectionKey`.

Reasons for choosing section bins first:

- it reuses Totem Lumen's established section coordinate system;
- negative coordinates already have well-defined floor semantics;
- moving entities only dirty a small number of section memberships;
- ray traversal can query a bounded candidate list for the current section rather than linearly scanning every entity in the scene;
- the same mechanism can later support P14D out-of-cell block-entity geometry.

The CPU broad phase uses a fixed default capacity of 32 entity references per section. Overflow is counted explicitly; it must never silently corrupt geometry. The final GPU capacity can be adjusted only after runtime measurements.

## Capture cache and lifecycle

Minecraft render states are not treated as persistent entity identity. `EntityRendererStateMixin` observes Minecraft 26.2's official-mapped `EntityRenderer.createRenderState(Entity, float)` and binds the returned render-state object to a Totem Lumen instance keyed by `(dimension id, Entity.getId())`. The later dispatcher/model capture uses that binding for the current frame.

This keeps one stable Totem Lumen instance id across per-frame render-state allocation while still ensuring the retained scene contains no live `Entity` reference. A fallback temporary id exists only as a diagnostic compatibility path if Minecraft changes extraction ordering.

Entries not observed for a small number of level ticks are removed together with their stable entity-id mapping. Disconnect/client shutdown clears all bindings and snapshots.

The first runtime diagnostic is:

```text
P17 dynamic entity geometry capture active: type=<entity type> quads=<count>
```

A fallback identity path additionally logs a warning and must not appear in a successful Alpha 42 runtime gate.

This proves Minecraft's live entity submit path reached the generic capture layer. It does **not** by itself prove GPU rendering is complete.

## Build-time Minecraft ABI gate

The existing mixin descriptor verifier checks all P17 Minecraft-facing methods against the actual Minecraft 26.2 client runtime classes:

- `EntityRenderer.createRenderState(Entity, float) -> EntityRenderState`;
- `EntityRenderDispatcher.submit(EntityRenderState, CameraRenderState, double, double, double, PoseStack, SubmitNodeCollector)`;
- the two `submitModel(...)` implementations already shared with P14D.

These are required mixins. A descriptor drift must fail CI instead of being discovered as an `InvalidInjectionException` at player startup.

## GPU integration

Alpha 42 is developed in explicit gates.

### P17A — capture + CPU broad phase

Status: **IMPLEMENTED / CI PASS**

- Player/general LivingEntity model submissions captured.
- Stable source entity identity survives temporary per-frame render states.
- Immutable scene snapshots produced.
- Section-binned broad phase implemented and unit tested.
- Minecraft-facing mixin descriptors verified against 26.2 during CI.

### P17B — Vulkan scene ABI + upload

Status: **IMPLEMENTED / CI PASS**

`GpuDynamicEntityScene` adds a separate bounded tail after the existing pixel target and P14 model-mesh tail. It does not widen the 32-bit voxel record.

Baseline capacities:

- 256 dynamic entity descriptors;
- 65,536 entity quads;
- 512 open-addressed section-candidate buckets;
- 32 entity candidates per section.

Each entity descriptor stores:

- stable 64-bit Totem Lumen instance id;
- integer origin-section coordinates;
- section-local entity origin;
- section-local world AABB;
- first-quad offset and quad count.

The section-candidate table reuses the same section hash semantics used by the static voxel lookup. Candidate overflow and maximum probe length are explicit diagnostics.

`P17DynamicEntityGpuUploader` tracks the entity-cache revision and can repack/flush/copy only the P17 tail on camera-only/static-world frames. Entity animation therefore does not force a full section-voxel repack.

The upload path logs:

```text
P17 entity GPU scene: entities=<n>, quads=<n>, sectionBuckets=<n>, overflow=<n>, maxProbe=<n>, ...
```

When the entity scene changes, Alpha 42 conservatively disables temporal-history reads for that frame. This prevents moving entities from leaving stale P10/P11/P12 history trails before P17-specific hit identity is incorporated into per-pixel history validation.

### P17C — trace integration

Status: **IN PROGRESS**

Extend the shared trace path so dynamic entity triangles compete with voxel/static-model hits for nearest distance. The same dynamic-geometry hit result must be visible to:

- camera rays;
- sun/moon visibility;
- local-light shadows;
- diffuse GI;
- environment/sky visibility;
- P15 transmission where the entity material permits it;
- P16 reflection.

No separate reflection-only or shadow-only entity geometry implementation is allowed.

The first P17C material result may use a conservative entity surface identity/color, but the hit must carry enough identity to avoid confusing moving entities with static voxel history.

### P17D — material baseline

Status: **PENDING**

The first accepted visual baseline may use a conservative entity material/color until texture sampling is connected, but entity geometry must not be presented as feature-complete material support.

Skin/texture alpha, armor/equipment texture semantics, emissive entity layers and resource-pack/PBR data remain separate material work.

## Runtime gate

Alpha 42 is accepted only when all of the following are demonstrated in-world:

1. a remote or third-person player produces captured dynamic geometry;
2. at least two ordinary living mobs with different skeletons/models produce captured geometry without entity-specific hooks;
3. walking, turning, crouching and limb animation update geometry without stale trails;
4. no `temporary instance id` fallback warning appears during the normal Player/LivingEntity validation path;
5. despawn/chunk movement/world rejoin does not retain stale entities;
6. `P17 entity GPU scene` reports nonzero entity/quad counts with no unexpected candidate overflow;
7. entity geometry appears in the Totem Lumen primary ray image;
8. entities cast directional/local-light shadows;
9. entities appear in P16 reflections;
10. existing P12-P16 static-world rendering remains functional;
11. no unbounded per-frame mesh-id/resource growth occurs.

## Explicit follow-ups

The first Alpha 42 domain does not claim complete support for every Minecraft entity renderer.

Separate adapters/follow-ups are expected for:

- dropped items / item renderer submissions;
- boats, rafts and minecarts if they use non-Model command paths;
- arrows/projectiles and special custom geometry;
- leashes;
- flames;
- text/display entities;
- custom geometry callbacks;
- texture-alpha silhouette testing;
- armor/equipment/material fidelity;
- first-person hand/held-item rendering, which is a separate view-model domain from world entities.

## Relationship to fluid geometry

Exact flowing/sloped water and lava geometry remains required, but it is moved behind P17 because missing players/mobs are a larger scene-completeness gap. The next planned milestone after Dynamic Entities is P14E exact fluid geometry.

Current sequence:

```text
Alpha 41 runtime readiness
  -> Alpha 42 / P17 Dynamic Entities
  -> Alpha 43 / P14E Exact Fluid Geometry
  -> P18 Resource Pack / LabPBR integration
```

## Architectural invariant

Entity rendering must consume Minecraft's renderer-resolved geometry and produce Totem Lumen-owned immutable scene data. Do not retain live entity/model objects in Vulkan code, do not create a table of hand-authored mob geometry, and do not make dynamic entity support depend on optional Vulkan hardware ray-tracing extensions.
