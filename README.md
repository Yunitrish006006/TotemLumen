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

`0.1.0-alpha.38` adds persistent Vulkan pipeline caching after Apple M4 + MoltenVK 1.4.2 runtime testing of Alpha 37 measured about 475.7 seconds for the P12-P15 base pipeline and 71.5 seconds for the split P16 reflection pipeline on a clean first compile.

- Totem Lumen persists opaque `VkPipelineCache` data under the Minecraft game cache directory;
- cache files are keyed by cache schema, Vulkan vendor/device, driver version and `pipelineCacheUUID`;
- base and P16 pipeline builds share the same persisted driver cache blob;
- each build uses a short-lived `VkPipelineCache`, writes updated cache data atomically, then destroys the cache handle before returning;
- a missing cache remains a normal first-run `MISS` and compiles through the existing background pipeline workers;
- incompatible/corrupt cache data is discarded and retried empty, while cache I/O failure falls back to normal no-cache pipeline creation;
- pipeline-cache persistence is strictly an optimization and never a renderer correctness dependency.

Alpha 37's P14C static block-model geometry remains the current geometry baseline: ordinary chunk-rendered block states use Minecraft/Fabric's emitted `BlockStateModel` quads instead of silently becoming full cubes, and the same P14 trace functions feed primary rays, shadows, GI, P15 transmission and P16 reflection. Special/block-entity renderers, exact fluid surfaces, texture-alpha cutout silhouettes and per-position/out-of-cell model transforms remain explicit later geometry domains. Dedicated-server runtime/TPS stress validation is still deferred while client renderer development continues.

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
- [`docs/PERSISTENT_VULKAN_PIPELINE_CACHE.md`](docs/PERSISTENT_VULKAN_PIPELINE_CACHE.md) — Alpha 37 startup measurements, Alpha 38 cache persistence design, fallback semantics and validation gate.
- [`docs/SERVER_GAMEPLAY_LIGHTING.md`](docs/SERVER_GAMEPLAY_LIGHTING.md) — authoritative RGB gameplay-lighting design, resource estimates, budgets and validation plan.
- [`docs/GAMEPLAY_LIGHTING_ROADMAP.md`](docs/GAMEPLAY_LIGHTING_ROADMAP.md) — implementation phases and follow-up work for the server subsystem.
- [`docs/LIGHTING_WORLD_RULES.md`](docs/LIGHTING_WORLD_RULES.md) — data-pack block lighting rule format.
- [`docs/PLANNING_INDEX.md`](docs/PLANNING_INDEX.md) — index of planning documents and decision tables.

## License

Apache-2.0, matching the existing Totem series repositories.
