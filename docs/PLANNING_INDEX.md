# Totem Lumen Planning Index

This file is the repository index for design plans and decision tables. Architectural decisions should be recorded in GitHub docs instead of existing only in chat history.

| Area | Primary document | What is recorded |
| --- | --- | --- |
| Whole project architecture | [`ARCHITECTURE.md`](ARCHITECTURE.md) | Vulkan/client vs common/server boundaries, authority model, package direction |
| Renderer phases | [`ROADMAP.md`](ROADMAP.md) | P0+ renderer milestones and runtime validation gates |
| P13 Overworld moon | [`P13_OVERWORLD_MOON.md`](P13_OVERWORLD_MOON.md) | moon disk, opposite-sun celestial direction, moonlight visibility, phase limitation and runtime gate |
| P14C generic block models | [`P14C_GENERIC_BLOCK_MODELS.md`](P14C_GENERIC_BLOCK_MODELS.md) | static BlockStateModel quad extraction, generic mesh ABI/GPU layout, geometry-domain completeness matrix and future-proofing rules |
| P14D block-entity geometry | [`P14D_BLOCK_ENTITY_GEOMETRY.md`](P14D_BLOCK_ENTITY_GEOMETRY.md) | renderer submit capture, static+BE composition, stable mutable mesh ids, model-tail updates, lifecycle and runtime gates |
| P16 reflection / roughness | [`P16_REFLECTION_ROUGHNESS.md`](P16_REFLECTION_ROUGHNESS.md) | surface fallback values, 32-bit voxel packing, Fresnel/reflection model, performance scope and runtime validation |
| P16 MoltenVK startup stalls | [`P16_MOLTENVK_PIPELINE_STALL.md`](P16_MOLTENVK_PIPELINE_STALL.md) | Alpha 34/35 runtime stalls, non-blocking pipeline prewarm and why waiting/cache alone is insufficient |
| P16 multi-pass split | [`P16_MULTIPASS_SPLIT.md`](P16_MULTIPASS_SPLIT.md) | Alpha 36 pass boundaries, shared-SSBO synchronization, independent reflection readiness, shader compile policy and fallback semantics |
| Persistent Vulkan pipeline cache | [`PERSISTENT_VULKAN_PIPELINE_CACHE.md`](PERSISTENT_VULKAN_PIPELINE_CACHE.md) | Alpha 37 startup measurements, Alpha 38 VkPipelineCache persistence, cache identity/fallback rules and runtime validation |
| Server gameplay-light phases | [`GAMEPLAY_LIGHTING_ROADMAP.md`](GAMEPLAY_LIGHTING_ROADMAP.md) | GL0–GL5 implementation/validation roadmap |
| Authoritative gameplay-light design | [`SERVER_GAMEPLAY_LIGHTING.md`](SERVER_GAMEPLAY_LIGHTING.md) | storage, propagation, spawn policy, sky/environment, budgets, resource estimates, validation matrix |
| Data-pack block lighting | [`LIGHTING_WORLD_RULES.md`](LIGHTING_WORLD_RULES.md) | `emission_color`, `gameplay_strength`, reload/sync semantics |
| Vulkan interoperability | [`VULKAN_INTEROP.md`](VULKAN_INTEROP.md) | Minecraft Vulkan ownership/interoperability constraints |

## Current accepted client-lighting decisions

| Topic | Accepted direction |
| --- | --- |
| Overworld sun source | captured Overworld clock drives one procedural sun direction and sun disk in P13 |
| Overworld moon source | moon direction is exactly the celestial opposite of the P13 sun direction and is horizon-gated independently |
| Moon sky appearance | procedural full disk plus low-intensity halo in Alpha 40; no separate sky texture/resource/pass |
| Moon surface lighting | weak cool directional term with the same ray-traced visibility semantics as sun lighting |
| Lunar phase | explicitly deferred; current packed environment state has no day index / 8-step phase and stochastic seed bits are not repurposed |
| Static block geometry source | resolved Minecraft/Fabric `BlockStateModel` emitted quads; block-id shape tables are not the general source of truth |
| Generic static geometry ABI | `MODEL_MESH (0xA000)` with 12-bit deduplicated mesh id in the existing 32-bit voxel word |
| Full-cube performance | detect canonical unit cubes and keep the established full-cube fast path |
| Generic geometry consumers | one shared P14 trace path for camera, shadows, GI, P13 sky, P15 transmission and P16 reflection |
| Model reload policy | retain old mesh ids while populated sections are refreshed through the bounded background extraction queue |
| P14C runtime gate | static/chunk `BlockStateModel` representative shapes passed Apple M4 runtime validation in Alpha 38; Bell exposed the separate block-entity renderer domain |
| Block-entity geometry source | capture renderer-resolved `Model` / `ModelPart` submissions inside `BlockEntityRenderDispatcher`; never retain mutable Minecraft render/model objects |
| Block-entity composition | renderer geometry supplements rather than replaces the owning block's P14C/static geometry |
| Animated block-entity meshes | one stable mutable mesh id per loaded `(dimension, block position)`; animation replaces the payload behind that id rather than allocating new ids |
| Block-entity GPU updates | registry revision may repack/copy only the shared scene-SSBO model tail; section voxel data stays unchanged after the initial geometry-code switch |
| Block-entity lifecycle | recycle dynamic ids on chunk unload and client-level changes |
| Specialized BE commands | Model/ModelPart is the generic P14D baseline; text/item/beam/portal/custom command families require explicit adapters rather than being claimed complete |
| Out-of-cell BE geometry | requires a later instance-bounds/broad-phase extension; owner-voxel DDA is not sufficient for arbitrary protruding models |
| Fluid geometry | separate renderer domain; exact flowing/sloped surfaces remain pending after P14D |
| Alpha-cutout geometry | emitted planes are represented; texture-alpha silhouette testing remains pending material integration |
| Out-of-cell / random-offset static models | require instance/broad-phase follow-up; do not destroy mesh dedup by baking position into every mesh id |
| Reflection baseline | one bounded secondary reflection ray in GI Composite |
| Roughness | 4-bit full-cube fallback profile; deterministic rough reflection direction |
| Metallic | 4-bit full-cube fallback profile; metallic F0 tint |
| Fresnel | Schlick approximation |
| Reflection distance | 64-block cap for P16 baseline |
| Reflection through glass | reuse P15 filtered/transmissive trace along the secondary ray |
| Glass interface reflection | deferred until refraction/Fresnel interface transport |
| Specular recursion | excluded from P16 |
| Surface source of truth | built-in fallback now; resource-pack/LabPBR later |
| Voxel memory growth | none for per-voxel records; P14C/P14D share the scene mesh pool rather than widening voxels |
| Base compute pipeline | P12-P15 only; renderer readiness must not depend on reflection compilation |
| Reflection compute pipeline | independent P16 pass over the same scene SSBO, dispatched before the existing buffer-to-image copy |
| Pass synchronization | compute shader-write -> shader-read/write SSBO barrier between base and reflection dispatches |
| Base GLSL -> SPIR-V | background shaderc prewarm at O0; current production source is verified in CI |
| Reflection GLSL -> SPIR-V | dedicated worker at O0; current production source is verified in CI |
| P16 shaderc performance optimization | rejected for current split source: produced 1,515,084-byte SPIR-V and about 20 s CI compile time |
| SPIR-V -> driver pipeline | background Vulkan pipeline work; never block the Minecraft render thread |
| Reflection failure policy | keep P12-P15 renderer active; disable only P16 reflection for that resource generation |
| MoltenVK/Vulkan pipeline cache | Alpha 38 persists driver/device-keyed opaque `VkPipelineCache` data; Apple M4 cache-hit runtime measured base 49 ms and P16 73 ms; cache remains an optimization, never a correctness dependency |
| Future shader growth | prefer bounded additional passes over rebuilding a P12+ monolithic mega-shader |
| Optimization policy | measure Apple/MoltenVK runtime cost before changing ray count/sampling |

## Current accepted server-lighting decisions

| Topic | Accepted direction |
| --- | --- |
| Authority | Server owns gameplay light; client owns presentation |
| Fixed block light | packed RGB section cache |
| Flickering visual lights | stable gameplay representative strength |
| True gameplay dynamic lights | future cached-spatial + dynamic-state layer |
| Sky/day cycle | query-time sky/time semantics, no per-tick RGB rebuild |
| Sun angle | client renders directional shadows; server does not ray trace sun |
| Dimension ambient | optional query-time dimension RGB profile |
| Nether mobs | reduced red sensitivity, especially environment red |
| Spawn query | O(1) stable lookup + spectral sensitivity + Vanilla sky/dimension checks |
| Backlog safety | dirty lighting suppresses hostile dark-spawn approval |
| Server GI | excluded |
| RGB persistence | excluded in first implementation |
| RGB network sync | excluded; only compact rules sync to clients |

## Current resource-planning tables

The canonical detailed server estimates live in `SERVER_GAMEPLAY_LIGHTING.md`. The headline budgets are:

| Resource | Baseline target |
| --- | --- |
| RGB cache | 8 KiB per allocated lit section |
| Idle CPU | < 0.2 ms/tick |
| Normal CPU | < 0.75 ms/tick |
| Heavy update CPU | < 1.5 ms/tick typical |
| Hard lighting time ceiling | about 2 ms/tick server-wide |
| Work ceiling | 20,000 work units/tick server-wide |
| Persistent world data | 0 bytes for RGB cache in v1 |
| Steady-state custom network traffic | approximately zero |

These CPU numbers are engineering targets, not benchmark claims. Runtime measurements are required before optimization decisions.
