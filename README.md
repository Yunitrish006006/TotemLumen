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

`0.1.0-alpha.37` adds the P14C static block-model geometry gate before P17 dynamic entities.

- ordinary chunk-rendered block states no longer rely on a short block-id shape table or default to full cubes;
- Totem Lumen asks Minecraft/Fabric's resolved `BlockStateModel` to emit its real quad geometry and copies only immutable vertex positions into a deduplicated client mesh registry;
- a new `MODEL_MESH` geometry family stores a 12-bit mesh id without widening the 32-bit per-voxel ABI;
- canonical full cubes retain the established fast path, while arbitrary model quads use block-local triangle intersection after voxel DDA broad phase;
- the same P14 trace functions are shared by primary rays, shadows, diffuse GI, environment visibility, P15 glass traversal and P16 reflection;
- model meshes live in a shared scene-SSBO tail and are uploaded with static scene revisions rather than every camera frame;
- resource-model reloads progressively refresh populated sections through the existing bounded background extraction queue.

This milestone deliberately does **not** claim that every Minecraft renderer domain is now identical to vanilla. Special/block-entity renderers, exact fluid surfaces, texture-alpha cutout silhouettes and per-position/out-of-cell model transforms are separate geometry domains documented in `P14C_GENERIC_BLOCK_MODELS.md`. Those domains must receive explicit extraction/validation gates rather than silently falling back to cubes. P16 multi-pass reflection from Alpha 36 and Alpha 32's server-authoritative gameplay lighting remain intact. Dedicated-server runtime/TPS stress validation is still deferred while client renderer development continues.

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
  -> static BlockStateModel quad extraction / immutable RayScene
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
- [`docs/P16_REFLECTION_ROUGHNESS.md`](docs/P16_REFLECTION_ROUGHNESS.md) — reflection model, surface profiles, ABI choice, limitations and validation plan.
- [`docs/P16_MOLTENVK_PIPELINE_STALL.md`](docs/P16_MOLTENVK_PIPELINE_STALL.md) — Alpha 34/35 MoltenVK pipeline findings and the Alpha 36 multi-pass resolution.
- [`docs/P16_MULTIPASS_SPLIT.md`](docs/P16_MULTIPASS_SPLIT.md) — Alpha 36 pass boundaries, synchronization, fallback semantics and runtime validation.
- [`docs/SERVER_GAMEPLAY_LIGHTING.md`](docs/SERVER_GAMEPLAY_LIGHTING.md) — authoritative RGB gameplay-lighting design, resource estimates, budgets and validation plan.
- [`docs/GAMEPLAY_LIGHTING_ROADMAP.md`](docs/GAMEPLAY_LIGHTING_ROADMAP.md) — implementation phases and follow-up work for the server subsystem.
- [`docs/LIGHTING_WORLD_RULES.md`](docs/LIGHTING_WORLD_RULES.md) — data-pack block lighting rule format.
- [`docs/PLANNING_INDEX.md`](docs/PLANNING_INDEX.md) — index of planning documents and decision tables.

## License

Apache-2.0, matching the existing Totem series repositories.
