# Totem Lumen

Totem Lumen is a Minecraft 26.2 Fabric lighting mod with two deliberately separate systems:

- a Vulkan-first, Vulkan-only client renderer for real-time voxel ray tracing, shadows, GI, temporal accumulation, denoising, transmission and composition;
- server-authoritative gameplay lighting for deterministic RGB light semantics used by spawning and future world rules.

The server gameplay subsystem never initializes or depends on Vulkan. A dedicated server can run Totem Lumen only for authoritative lighting/world rules, while compatible clients render the same server-owned emission colors with their local Vulkan renderer.

## Project goals

- Vulkan-only client rendering path. No OpenGL implementation or fallback inside Totem Lumen.
- Vulkan compute voxel ray tracing is the cross-platform baseline.
- Apple Silicon is a first-class target through Minecraft 26.2's Vulkan backend and MoltenVK/Metal path.
- Vulkan hardware ray tracing is optional acceleration, never a required baseline.
- Server-authoritative gameplay lighting must remain deterministic, bounded, and independent of client rendering settings.
- Player runtime dependencies should remain limited to Fabric Loader, Fabric API, and Totem Lumen.
- Shader binaries and other runtime assets must ship inside the mod; players should not need the Vulkan SDK, MoltenVK, Xcode, RenderDoc, or shader compilers.

## Current milestone

`0.1.0-alpha.42` advances P17 Dynamic Entities. P17A capture/broad-phase and P17B bounded Vulkan scene upload are implemented and passing CI; P17C shared nearest-hit shader integration is now the active gate.

- `EntityRenderer.createRenderState(...)` binds temporary Minecraft render states to stable `(dimension, Entity.getId())` identities;
- `EntityRenderDispatcher.submit(...)` establishes one capture scope around each entity renderer submission;
- the existing Minecraft 26.2 `submitModel(...)` interception is shared by P14D block entities and P17 living entities instead of adding mob-specific hooks;
- captured entity meshes retain Minecraft's resolved animation/pose transforms while removing camera-relative entity placement;
- `DynamicEntitySnapshot` stores only copied entity-local geometry, double-precision absolute world position and derived world AABB; no live Minecraft entity/model/render-state object enters the retained scene;
- `DynamicEntityBroadPhase` bins AABBs into overlapping 16x16x16 sections with a bounded 32-candidate baseline and explicit overflow accounting;
- the P17B GPU ABI uses integer section origins plus section-local floats, up to 256 entity descriptors, 65,536 quads and 512 section-candidate hash buckets;
- P17 entity revisions repack/flush/copy only the dedicated entity scene tail instead of forcing static voxel re-uploads;
- entity-scene changes conservatively disable temporal-history reads for that frame to avoid moving-entity trails before P17 hit identity is integrated into history validation;
- required Minecraft 26.2 entity/mixin descriptors are verified against the actual client runtime classes in CI;
- P17C is not complete yet, so this branch does **not** yet claim that players/mobs appear in Totem Lumen ray output.

Alpha 41's renderer readiness state machine remains the startup baseline. Totem Lumen does not enter its render path until Minecraft's Vulkan bridge and background shader/pipeline prewarm are genuinely ready.

Alpha 40's P13 Overworld night sky remains fully included: procedural moon, Minecraft's resolved eight-step lunar phase, and a deterministic procedural star field remain present when Totem Lumen owns the GI Composite output.

- the moon direction is the exact celestial opposite of the existing time-of-day sun direction;
- `P13EnvironmentCapture` reads Minecraft 26.2's resolved `EnvironmentAttributes.MOON_PHASE`, preserving the vanilla full/waning/quarter/crescent/new/waxing phase order;
- a procedural moon disk uses a curved terminator to render distinct full, gibbous, quarter, crescent and new-moon silhouettes without adding a texture or render pass;
- moon halo and directional moonlight strength track the eight phase factors `1.0 / 0.75 / 0.5 / 0.25 / 0.0 / 0.25 / 0.5 / 0.75`;
- the 32-bit frame environment word uses 2 dimension bits + 11 day-phase bits + 3 lunar-phase bits + the original 16-bit stochastic seed, so temporal/GI seed range is preserved;
- `P13StarfieldPatch` adds sparse deterministic stars that fade in at night, fade near the horizon and rotate with the captured day phase without per-frame twinkle;
- Overworld surfaces receive a separate weak cool moon directional term with the same ray-traced visibility semantics used by the sun, including P15 RGB glass transmission;
- weather-dependent star suppression / Minecraft `STAR_BRIGHTNESS` integration remains a later environment-state follow-up.

Alpha 39's P14D block-entity geometry capture remains the block-entity baseline. `BlockEntityRenderDispatcher` scopes renderer-resolved `Model` / `ModelPart` capture into Totem Lumen-owned block-local quads, stable mutable `MODEL_MESH` ids preserve animation without exhausting the 12-bit mesh space, and chunk/level lifecycle recycles dynamic mesh ids.

Exact flowing/sloped water and lava surfaces remain required, but the planned order is now Dynamic Entities first because missing players/mobs are a larger scene-completeness gap. The next planned milestone after Alpha 42 is P14E Exact Fluid Geometry.

Alpha 38's persistent Vulkan pipeline cache remains enabled. Apple M4 + MoltenVK 1.4.2 runtime cache-hit measurements reduced P12-P15 pipeline creation from about 475.7 seconds to 49 ms and P16 from about 71.5 seconds to 73 ms. Pipeline caching remains an optimization, not a correctness dependency. Dedicated-server runtime/TPS stress validation is still deferred while client renderer development continues.

## Runtime requirements

- Minecraft Java Edition 26.2
- Fabric Loader 0.19.5 or newer
- Fabric API for Minecraft 26.2
- Java 25 (normally provided by the Minecraft launcher/server runtime)
- A Minecraft-compatible Vulkan backend **only when using the client renderer**

The client renderer must actually be launched with Minecraft's Vulkan backend. Totem Lumen intentionally has no OpenGL renderer or fallback. Development `runClient` already requests Vulkan; normal launcher profiles must also be configured to use Vulkan.

A dedicated server does not need Vulkan, MoltenVK, a GPU renderer, or shader tooling.

On Apple Silicon, the client uses the Vulkan backend exposed by Minecraft. Minecraft handles the MoltenVK-to-Metal translation; users should not install MoltenVK separately.

## Development

```text
Server world/data packs
  -> Totem Lumen authoritative RGB gameplay light field
  -> spawning / future gameplay queries

Minecraft client extraction / submission
  -> static BlockStateModel quad extraction
  -> block-entity renderer Model/ModelPart capture
  -> dynamic living-entity Model capture
  -> immutable/copy-owned scene
  -> static model mesh pool + dynamic entity broad phase
  -> Vulkan GPU scene
       -> static voxel/model regions
       -> independently updated P17 entity tail
  -> P12-P15 base compute pass
       -> P13 sun / sky / moon + lunar phase + procedural stars
  -> P16 reflection compute pass
  -> composition
```

Current scene-quality sequence:

```text
Alpha 41 runtime readiness
  -> Alpha 42 / P17 Dynamic Entities
  -> Alpha 43 / P14E Exact Fluid Geometry
  -> P18 Resource Pack / LabPBR integration
```

Development client runs request the Vulkan backend.

Until the Gradle wrapper binary is generated in-repository, use Gradle 9.5.1 locally:

```bash
gradle build
gradle runClient
```

CI installs Gradle 9.5.1 explicitly.

## Documentation

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — client/server layering and hard architectural boundaries.
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — renderer roadmap and completed milestones.
- [`docs/P13_OVERWORLD_MOON.md`](docs/P13_OVERWORLD_MOON.md) — Alpha 40 Overworld night sky: moon disk, eight-step lunar phase, procedural stars, transport and runtime validation.
- [`docs/P14C_GENERIC_BLOCK_MODELS.md`](docs/P14C_GENERIC_BLOCK_MODELS.md) — generic static block-model extraction, generic mesh ABI/GPU layout, geometry-domain completeness matrix and future-proofing rules.
- [`docs/P14D_BLOCK_ENTITY_GEOMETRY.md`](docs/P14D_BLOCK_ENTITY_GEOMETRY.md) — block-entity renderer geometry capture, stable mutable mesh slots, lifecycle, limitations and runtime validation.
- [`docs/P17_DYNAMIC_ENTITIES.md`](docs/P17_DYNAMIC_ENTITIES.md) — Alpha 42 Player/LivingEntity capture, dynamic broad phase, Vulkan scene ABI/upload, trace-integration gates and runtime validation.
- [`docs/P16_REFLECTION_ROUGHNESS.md`](docs/P16_REFLECTION_ROUGHNESS.md) — surface fallback values, reflection model, performance scope and runtime validation.
- [`docs/P16_MOLTENVK_PIPELINE_STALL.md`](docs/P16_MOLTENVK_PIPELINE_STALL.md) — Alpha 34/35 MoltenVK pipeline findings and the Alpha 36 multi-pass resolution.
- [`docs/P16_MULTIPASS_SPLIT.md`](docs/P16_MULTIPASS_SPLIT.md) — Alpha 36 pass boundaries, synchronization, fallback semantics and runtime validation.
- [`docs/PERSISTENT_VULKAN_PIPELINE_CACHE.md`](docs/PERSISTENT_VULKAN_PIPELINE_CACHE.md) — Alpha 37 startup measurements, Alpha 38 VkPipelineCache persistence design, fallback semantics and validation gate.
- [`docs/SERVER_GAMEPLAY_LIGHTING.md`](docs/SERVER_GAMEPLAY_LIGHTING.md) — authoritative RGB gameplay-lighting design, resource estimates, budgets and validation plan.
- [`docs/GAMEPLAY_LIGHTING_ROADMAP.md`](docs/GAMEPLAY_LIGHTING_ROADMAP.md) — implementation phases and follow-up work for the server subsystem.
- [`docs/LIGHTING_WORLD_RULES.md`](docs/LIGHTING_WORLD_RULES.md) — data-pack block lighting rule format.
- [`docs/PLANNING_INDEX.md`](docs/PLANNING_INDEX.md) — index of planning documents and decision tables.

## License

Apache-2.0, matching the existing Totem series repositories.
