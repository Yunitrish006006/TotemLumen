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

`0.1.0-alpha.33` adds the P16 client reflection/roughness baseline:

- one bounded secondary reflection ray in the GI composite path;
- roughness-controlled deterministic reflection spread;
- Schlick Fresnel response and metallic F0 tint;
- built-in client surface profiles until resource-pack/LabPBR integration;
- 4-bit roughness + 4-bit metallic packed into the existing 32-bit voxel word;
- reflected rays reuse P14 geometry and P15 RGB filtered glass transmission;
- no recursive specular transport or additional per-voxel GPU buffer.

Alpha 32's server-authoritative RGB gameplay-lighting baseline remains intact. Dedicated-server runtime/TPS stress validation is intentionally deferred while client renderer development continues.

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
  -> Totem Lumen RayScene
  -> Vulkan GPU scene
  -> Vulkan compute voxel RT
  -> lighting / temporal / denoise / GI / reflection
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
- [`docs/P16_REFLECTION_ROUGHNESS.md`](docs/P16_REFLECTION_ROUGHNESS.md) — Alpha 33 reflection model, surface profiles, ABI choice, limitations and validation plan.
- [`docs/SERVER_GAMEPLAY_LIGHTING.md`](docs/SERVER_GAMEPLAY_LIGHTING.md) — authoritative RGB gameplay-lighting design, resource estimates, budgets and validation plan.
- [`docs/GAMEPLAY_LIGHTING_ROADMAP.md`](docs/GAMEPLAY_LIGHTING_ROADMAP.md) — implementation phases and follow-up work for the server subsystem.
- [`docs/LIGHTING_WORLD_RULES.md`](docs/LIGHTING_WORLD_RULES.md) — data-pack block lighting rule format.
- [`docs/PLANNING_INDEX.md`](docs/PLANNING_INDEX.md) — index of planning documents and decision tables.

## License

Apache-2.0, matching the existing Totem series repositories.
