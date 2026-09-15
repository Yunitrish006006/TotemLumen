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

`0.1.0-alpha.39` adds P14D block-entity geometry capture after Alpha 38 runtime validation confirmed the P14C static/chunk `BlockStateModel` gate and exposed the bell's renderer-owned hanging body as a separate geometry domain.

- `BlockEntityRenderDispatcher` establishes a capture scope around each block-entity renderer submission;
- renderer-resolved `Model` / `ModelPart` commands are copied into Totem Lumen-owned block-local quad arrays; mutable Minecraft model/render objects are not retained;
- captured block-entity quads supplement the owning block's P14C geometry instead of replacing it;
- each loaded block entity uses one stable mutable `MODEL_MESH` id keyed by dimension and block position, so animation updates replace mesh payloads without consuming a new 12-bit id every frame;
- dynamic mesh ids are recycled on chunk unload and client-level changes;
- mesh revisions can repack and copy only the shared scene-SSBO model tail, avoiding a full section-voxel repack for every animated pose;
- camera, shadow, GI, environment, P15 transmission and P16 reflection rays continue to consume the same shared P14 mesh traversal.

P14D's first runtime gate is the bell body that was missing in Alpha 38. CI verifies the stable mutable mesh-slot lifecycle, but Bell/chest/shulker animation correctness still requires in-game validation. Specialized block-entity renderer commands such as text/items/beams/portals/custom primitives are not silently claimed as covered; adapters are added by renderer command family. Exact flowing/sloped fluid surfaces remain the next pre-P17 geometry domain. Out-of-cell models still require a later instance-bounds/broad-phase extension.

Alpha 38's persistent Vulkan pipeline cache remains enabled. Apple M4 + MoltenVK 1.4.2 runtime cache-hit measurements reduced P12-P15 pipeline creation from about 475.7 seconds to 49 ms and P16 from about 71.5 seconds to 73 ms. Pipeline caching remains an optimization, not a correctness dependency. Dedicated-server runtime/TPS stress validation is still deferred while client renderer development continues.

## Runtime requirements

- Minecraft Java Edition 26.2
- Fabric Loader 0.19.5 or newer
- Fabric API for Minecraft 26.2
- Java 25 (normally provided by the Minecraft launcher/server runtime)
- A Minecraft-compatible Vulkan backend **only when using the client renderer**

A dedicated server does not need Vulkan, MoltenVK, a GPU renderer, or shader tooling.

On Apple Silicon, the client uses the Vulkan backend exposed by Minecraft. Minecraft handles the MoltenVK-to-Metal translation; users should not install MoltenVK separately.

## Development

```text
Server world/data packs
  -> Totem Lumen authoritative RGB gameplay light field
  -> spawning / future gameplay queries

Minecraft client extraction
  -> static BlockStateModel quad extraction
  -> block-entity renderer Model/ModelPart capture
  -> immutable/copy-owned scene + shared mutable mesh slots
  -> Vulkan GPU scene + shared generic model mesh pool
  -> P12-P15 base compute pass
  -> P16 reflection compute pass
  -> composition
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
- [`docs/P14C_GENERIC_BLOCK_MODELS.md`](docs/P14C_GENERIC_BLOCK_MODELS.md) — generic static block-model extraction, GPU ABI, geometry-domain matrix, limits and validation plan.
- [`docs/P14D_BLOCK_ENTITY_GEOMETRY.md`](docs/P14D_BLOCK_ENTITY_GEOMETRY.md) — block-entity renderer geometry capture, stable mutable mesh slots, lifecycle, limitations and runtime validation.
- [`docs/P16_REFLECTION_ROUGHNESS.md`](docs/P16_REFLECTION_ROUGHNESS.md) — reflection model, surface profiles, ABI choice, limitations and validation plan.
- [`docs/P16_MOLTENVK_PIPELINE_STALL.md`](docs/P16_MOLTENVK_PIPELINE_STALL.md) — Alpha 34/35 MoltenVK pipeline findings and the Alpha 36 multi-pass resolution.
- [`docs/P16_MULTIPASS_SPLIT.md`](docs/P16_MULTIPASS_SPLIT.md) — Alpha 36 pass boundaries, synchronization, fallback semantics and runtime validation.
- [`docs/PERSISTENT_VULKAN_PIPELINE_CACHE.md`](docs/PERSISTENT_VULKAN_PIPELINE_CACHE.md) — Alpha 37 startup measurements, Alpha 38 cache persistence design, fallback semantics and validation gate.
- [`docs/SERVER_GAMEPLAY_LIGHTING.md`](docs/SERVER_GAMEPLAY_LIGHTING.md) — authoritative RGB gameplay-lighting design, resource estimates, budgets and validation plan.
- [`docs/GAMEPLAY_LIGHTING_ROADMAP.md`](docs/GAMEPLAY_LIGHTING_ROADMAP.md) — implementation phases and follow-up work for the server subsystem.
- [`docs/LIGHTING_WORLD_RULES.md`](docs/LIGHTING_WORLD_RULES.md) — data-pack block lighting rule format.
- [`docs/PLANNING_INDEX.md`](docs/PLANNING_INDEX.md) — index of planning documents and decision tables.

## License

Apache-2.0, matching the existing Totem series repositories.
