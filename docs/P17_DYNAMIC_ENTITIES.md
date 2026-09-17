# P17 Dynamic Entities — Alpha 42

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

1. establish a capture scope around one entity submission;
2. observe renderer-resolved `Model` commands already selected by Minecraft;
3. apply the exact render state to the model before copying geometry;
4. copy only transformed primitive positions into Totem Lumen-owned arrays;
5. retain no live Minecraft `Entity`, `Model`, `ModelPart`, render-state, or `PoseStack` objects in the ray-tracing scene.

Alpha 42 initially captures only `LivingEntityRenderState` scopes. Other entity command families are explicit follow-ups.

## Coordinate model

Entity render submission receives a camera-relative placement `(renderX, renderY, renderZ)` plus a `PoseStack`. Totem Lumen captures the dispatcher entry pose and removes only the dispatcher placement from submitted model vertices.

The retained geometry is therefore entity-local but already includes renderer-selected orientation, animation, baby/adult scaling, sleeping/swimming/crouching transforms, model-part transforms, and other pose changes applied by the renderer.

The immutable snapshot separately stores the entity render state's absolute world position. World-space bounds are derived as:

```text
worldBound = entityWorldPosition + capturedEntityLocalVertex
```

This separation is required so future GPU upload can update an instance transform independently of shared/static mesh data when possible.

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

`EntityRenderGeometryCache` assigns weak render-state identities to Totem Lumen instance ids and retains only immutable snapshots. Entries not observed for a small number of level ticks are removed. Disconnect/client shutdown clears the cache.

The first runtime diagnostic is:

```text
P17 dynamic entity geometry capture active: type=<entity type> quads=<count>
```

This proves Minecraft's live entity submit path reached the generic capture layer. It does **not** by itself prove GPU rendering is complete.

## GPU integration plan

Alpha 42 is developed in explicit gates.

### P17A — capture + CPU broad phase

- Player/general LivingEntity model submissions captured.
- Immutable scene snapshots produced.
- Section-binned broad phase implemented and unit tested.
- No change to final ray result yet.

### P17B — Vulkan scene ABI

Add bounded dynamic-entity regions to the shared scene SSBO:

- entity descriptor table;
- world AABB / instance transform data;
- per-section entity candidate lists;
- dynamic quad/triangle pool.

Do not widen the existing 32-bit voxel record.

Dynamic entity uploads must be isolated from static section voxel uploads so animation does not force voxel repacks.

### P17C — trace integration

Extend the shared trace path so dynamic entity triangles compete with voxel/static-model hits for nearest distance. The same dynamic-geometry hit result must be visible to:

- camera rays;
- sun/moon visibility;
- local-light shadows;
- diffuse GI;
- environment/sky visibility;
- P15 transmission where the entity material permits it;
- P16 reflection.

No separate reflection-only or shadow-only entity geometry implementation is allowed.

### P17D — material baseline

The first accepted visual baseline may use a conservative entity material/color until texture sampling is connected, but entity geometry must not be presented as feature-complete material support.

Skin/texture alpha, armor/equipment texture semantics, emissive entity layers and resource-pack/PBR data remain separate material work.

## Runtime gate

Alpha 42 is accepted only when all of the following are demonstrated in-world:

1. a remote or third-person player produces captured dynamic geometry;
2. at least two ordinary living mobs with different skeletons/models produce captured geometry without entity-specific hooks;
3. walking, turning, crouching and limb animation update geometry without stale trails;
4. despawn/chunk movement/world rejoin does not retain stale entities;
5. entity geometry appears in the Totem Lumen primary ray image;
6. entities cast directional/local-light shadows;
7. entities appear in P16 reflections;
8. existing P12-P16 static-world rendering remains functional;
9. no unbounded per-frame mesh-id/resource growth occurs;
10. section candidate overflow is zero in the validation scene or is reported explicitly if the stress case exceeds the configured capacity.

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
