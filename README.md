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

The current development baseline is **Alpha 59**.

Client renderer state:

- Alpha 51+ uses direct world takeover: once a complete Totem frame is ready, Totem presents into Minecraft's main render target and vanilla level drawing is skipped. HUD/GUI remain independent.
- Alpha 53 bounds internal ray-tracing pixel work per quality preset so fullscreen framebuffer size no longer multiplies RT cost without limit.
- Alpha 57 retunes GI to 1/2/3 samples and ray distance to 32/64/96/128 blocks with 64 as the default.
- Alpha 58/59 replace the old renderer on/off UI with a persistent **Minecraft / Totem Lumen render profile** directly inside Video Settings -> Quality & Performance.
- P14E exact water/lava geometry is implemented and shares the nearest-hit path with static geometry, entities and reflection; detailed runtime geometry/optics validation remains.
- P17 Player/LivingEntity dynamic geometry and the generic entity material ABI are implemented. Spider-eye emissive behavior is runtime-validated. Items, vehicles, projectiles, display/text/custom renderers, armor/equipment, alpha silhouettes and first-person view models remain follow-ups.
- P18A/P18B/P18C LabPBR integration is implemented: renderer-resolved texture identity, bounded PBR texture data, shared normal/AO/roughness/metal/emission shading, alpha coverage and animated PBR frame selection. P18D entity/block-entity fidelity remains.
- Alpha 49's contiguous P17 upload is the accepted MoltenVK upload path after Alpha 48's split-copy regression.
- Whole-mega-shader optimization is not the current strategy: shaderc Osize failed with SPIR-V ID overflow. Future performance work targets measured hot paths, reusable primary-hit/material data and smaller bounded passes.
- Server gameplay lighting GL0 is complete; GL1/GL2 are implemented pending runtime validation; GL4 profiling/scale validation is the next server-side gate.

The authoritative milestone/status list lives in [docs/ROADMAP.md](docs/ROADMAP.md), with detailed design/status documents indexed by [docs/PLANNING_INDEX.md](docs/PLANNING_INDEX.md).

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
  -> authoritative RGB gameplay-light rules / packed gameplay-light field
  -> hostile-spawn and future deterministic gameplay queries

Minecraft client extraction / submission
  -> static BlockStateModel geometry
  -> block-entity Model/ModelPart geometry
  -> exact P14E fluid faces
  -> dynamic Player/LivingEntity geometry
  -> P18 renderer-resolved texture/material data
  -> immutable/copy-owned CPU scene
  -> Vulkan GPU scene
       -> static voxel/model regions
       -> fluid tail
       -> P17 dynamic entity/material tail
       -> P18 texture/material tail
  -> unified full-lighting compute
       -> primary visibility
       -> P13 environment / sun / moon / stars
       -> P15 transmission
       -> local lights
       -> one-bounce GI
       -> P17 entity nearest-hit
       -> P18 LabPBR shading
  -> optional P16 reflection compute pass
  -> direct world takeover presentation into Minecraft main render target
  -> hand / HUD / GUI
```

Current execution direction:

```text
Alpha 59 baseline
  -> finish P14D/P14E/P17/P18 runtime and coverage gates
  -> primary-hit/G-buffer + reflection/GI performance architecture
  -> GL4 gameplay-light profiling / GL5 hardening
  -> P19+ hardware RT / ReSTIR / adaptive sampling / dynamic resolution
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
- [`docs/P14E_EXACT_FLUID_GEOMETRY.md`](docs/P14E_EXACT_FLUID_GEOMETRY.md) — exact renderer-resolved water/lava geometry, GPU ABI, optics and runtime validation.
- [`docs/P17_DYNAMIC_ENTITIES.md`](docs/P17_DYNAMIC_ENTITIES.md) — Player/LivingEntity capture, dynamic broad phase, generic material ABI, upload/performance work and remaining entity renderer coverage.
- [`docs/P18_RESOURCE_PACK_LABPBR.md`](docs/P18_RESOURCE_PACK_LABPBR.md) — LabPBR resource-pack integration, shared PBR shading, alpha coverage, animated textures and P18D follow-ups.
- [`docs/RENDERER_SETTINGS.md`](docs/RENDERER_SETTINGS.md) — Alpha 59 render profiles, Quality & Performance UI, runtime quality ranges and scene-header controls.
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
