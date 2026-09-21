# P17 Dynamic Entities — Alpha 42

## Status

- **P17A capture + CPU broad phase: IMPLEMENTED / CI PASS**
- **P17B bounded Vulkan scene ABI + independent tail upload: IMPLEMENTED / CI PASS**
- **P17C shared nearest-hit shader integration: IMPLEMENTED / CI PASS, RUNTIME VALIDATION ACTIVE**
- **P17D generic entity material ABI: IMPLEMENTED / CI GATED**

Alpha 42 does not yet claim visually complete entity ray tracing until the runtime gate passes.

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
- copied entity-local quad positions;
- copied per-vertex UV coordinates used for data-driven entity material sampling.

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

## Alpha 45 runtime performance work

Alpha 45 adds a no-quality-loss P17 broad-phase optimization for the low-FPS path observed during
Alpha 44 validation.

The P17 GPU header now carries conservative global min/max entity section bounds. Every entity-aware
ray first intersects this global region. Rays that do not cross the region return immediately
without walking the 512-bucket section hash at all. Rays that do cross it begin their section DDA at
the global-bounds entry point instead of walking empty sections from the original camera, GI,
shadow, transmission or reflection ray origin.

This changes only broad-phase work. Dynamic entity triangle/AABB tests and nearest-hit ordering are
unchanged.

The capture path also removes two high-frequency Java allocation patterns:

- primitive positions/UVs use growable float arrays instead of `ArrayList<Float>` boxing;
- per-vertex `Vector3f` creation and temporary 16-float matrix arrays are removed from the hot path.

These changes specifically target fixed P17 cost that remained active even when internal resolution,
GI quality, shadow quality and reflections were reduced.

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
- first-quad offset and quad count;
- a generic entity-material slot.

Each quad stores four entity-local positions plus four UV pairs. Entity materials use a fixed
16-word descriptor table and a shared bounded texture pool. Slot 0 is the stable baseline material;
additional slots are resolved from entity type ids on the CPU.

The section-candidate table reuses the same section hash semantics used by the static voxel lookup. Candidate overflow and maximum probe length are explicit diagnostics.

`P17DynamicEntityGpuUploader` tracks the entity-cache revision and can repack/flush/copy only the P17 tail on camera-only/static-world frames. Entity animation therefore does not force a full section-voxel repack.

The upload path logs:

```text
P17 entity GPU scene: entities=<n>, quads=<n>, sectionBuckets=<n>, overflow=<n>, maxProbe=<n>, ...
```

When the entity scene changes, Alpha 42 conservatively disables temporal-history reads for that frame. This prevents moving entities from leaving stale P10/P11/P12 history trails before P17-specific hit identity is incorporated into per-pixel history validation.

### P17C — trace integration

Status: **IMPLEMENTED / CI PASS, RUNTIME VALIDATION ACTIVE**

Dynamic entity triangles compete with voxel/static-model hits for nearest distance. The same dynamic-geometry hit result is visible to:

- camera rays;
- sun/moon visibility;
- local-light shadows;
- diffuse GI;
- environment/sky visibility;
- P15 transmission where the entity material permits it;
- P16 reflection.

No separate reflection-only or shadow-only entity geometry implementation is used.

The first P17C material result uses a conservative opaque entity surface identity. P17D will replace that baseline with texture/material fidelity.

### Apple M4 / MoltenVK cold-compile architecture

Runtime measurements on Apple M4 + MoltenVK 1.4.2 showed that the former split solved readiness isolation but created a much larger cold-start cost:

```text
full lighting Vulkan pipeline:        992,772 ms  (~16m33s)
separate entity-enhanced base:      1,217,001 ms  (~20m17s)
warm-cache repeats:                         7–8 ms
```

The separate entity pipeline was not small: it rebuilt almost the entire GI / environment / block geometry / fluid / transmission / PBR control flow and then added entity tracing. That made the driver compile two near-duplicate 100k+ character compute shaders.

The current architecture therefore uses one production full-lighting pipeline:

1. the tiny bootstrap remains the only early Vulkan readiness bridge and is never shown to the player;
2. the production full-lighting shader includes static geometry, exact fluids, PBR/material shading and P17 dynamic-entity nearest-hit tracing in one source;
3. there is no second `totem_lumen_p17_dynamic_entities.comp` Vulkan pipeline at runtime;
4. the existing `P17EnhancedBasePipeline` class is only a compatibility facade for dispatch/status hooks and delegates to the unified full-lighting program;
5. P17 entity geometry still participates in primary rays, sun/moon/local-light visibility, diffuse GI and P15 transmission because the shared `traceRayLimited()` path remains entity-aware;
6. P16 reflection remains a separate optional pass and includes P17 tracing independently;
7. Minecraft's normal presentation remains visible until the unified full-lighting pipeline is ready, so a long first-run Metal compile never exposes a partial Totem renderer.

CI now enforces the inverse of the old isolation rule: the unified production base **must** contain the P17 entity markers, and there is no second full-base shaderc compile for a P17 variant.

The key build diagnostic is:

```text
Unified full-lighting verification PASS:
staticWorld=true, fluids=true, pbr=true, dynamicEntities=true,
duplicateEntityPipeline=false
```

### P17D — generic entity material ABI

Status: **IMPLEMENTED / CI GATED**

Entity materials no longer require entity-specific shader branches. The production shader consumes a
fixed material slot range and a generic descriptor containing:

- optional albedo texture;
- optional emissive texture;
- emissive gain;
- alpha cutoff;
- roughness;
- metallic;
- reflection scale;
- reserved words for later material fields.

Entity type ids, resource paths and per-entity tuning are resolved entirely on the CPU through:

```text
assets/totem-lumen/entity_material_rules.json
```

For example, spider/cave-spider albedo and eye overlays are data entries only. The shader does not
contain spider identifiers, spider flags or spider-specific sampling functions.

This is the required cache-invalidation boundary:

```text
add/tune entity material rule or texture
    -> entity GPU data changes
    -> generated GLSL unchanged
    -> SPIR-V / MoltenVK pipeline cache stays reusable

change entity tracing algorithm or material ABI layout
    -> generated GLSL changes
    -> one-time pipeline rebuild
```

The generic ABI currently handles entity albedo/emissive sampling and optical scalars. Armor,
equipment, render-layer-specific materials, texture-alpha hit rejection and non-Model renderer
families remain separate follow-ups.

## Runtime gate

Alpha 42 is accepted only when all of the following are demonstrated in-world:

1. a remote or third-person player produces captured dynamic geometry;
2. at least two ordinary living mobs with different skeletons/models produce captured geometry without entity-specific hooks;
3. walking, turning, crouching and limb animation update geometry without stale trails;
4. no `temporary instance id` fallback warning appears during the normal Player/LivingEntity validation path;
5. despawn/chunk movement/world rejoin does not retain stale entities;
6. `P17 entity GPU scene` reports nonzero entity/quad counts with no unexpected candidate overflow;
7. the base renderer reaches READY independently of P17 enhanced-pipeline readiness;
8. entity geometry appears in the Totem Lumen primary ray image after P17 enhanced-pipeline readiness;
9. entities cast directional/local-light shadows;
10. entities appear in P16 reflections;
11. existing P12-P16 static-world rendering remains functional;
12. no unbounded per-frame mesh-id/resource growth occurs.

Expected readiness diagnostics after the split are:

```text
Background Vulkan pipeline creation COMPLETE: shader=totem_lumen_p12_one_bounce_gi.comp
Renderer state: READY_FOR_SCENE_EXTRACTION
P5 stable GPU lookup READY: ...
P17 enhanced pipeline creation START: shader=totem_lumen_p17_dynamic_entities.comp ...
```

The P17 enhanced pipeline may complete later:

```text
P17 enhanced pipeline creation COMPLETE: ...
P17 dynamic entity tracing READY: enhanced base pipeline selected for frame dispatch
```

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
