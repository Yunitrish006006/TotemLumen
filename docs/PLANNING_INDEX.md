# Totem Lumen Planning Index

This file is the repository index for design plans and decision tables. Architectural decisions should be recorded in GitHub docs instead of existing only in chat history.

| Area | Primary document | What is recorded |
| --- | --- | --- |
| Whole project architecture | [`ARCHITECTURE.md`](ARCHITECTURE.md) | Vulkan/client vs common/server boundaries, authority model, package direction |
| Renderer phases | [`ROADMAP.md`](ROADMAP.md) | P0+ renderer milestones and runtime validation gates |
| P13 Overworld moon | [`P13_OVERWORLD_MOON.md`](P13_OVERWORLD_MOON.md) | moon disk, opposite-sun celestial direction, eight-step lunar phase, moonlight visibility and runtime gate |
| P14C generic block models | [`P14C_GENERIC_BLOCK_MODELS.md`](P14C_GENERIC_BLOCK_MODELS.md) | static BlockStateModel quad extraction, generic mesh ABI/GPU layout, geometry-domain completeness matrix and future-proofing rules |
| P14D block-entity geometry | [`P14D_BLOCK_ENTITY_GEOMETRY.md`](P14D_BLOCK_ENTITY_GEOMETRY.md) | renderer submit capture, static+BE composition, stable mutable mesh ids, model-tail updates, lifecycle and remaining specialized command families |
| P14E exact fluid geometry | [`P14E_EXACT_FLUID_GEOMETRY.md`](P14E_EXACT_FLUID_GEOMETRY.md) | renderer-resolved fluid faces, Fluid ABI v2, nearest-hit integration, water/lava optics and runtime validation matrix |
| P16 reflection / roughness | [`P16_REFLECTION_ROUGHNESS.md`](P16_REFLECTION_ROUGHNESS.md) | surface fallback values, 32-bit voxel packing, Fresnel/reflection model, performance scope and runtime validation |
| P16 MoltenVK startup stalls | [`P16_MOLTENVK_PIPELINE_STALL.md`](P16_MOLTENVK_PIPELINE_STALL.md) | Alpha 34/35 runtime stalls, non-blocking pipeline prewarm and why waiting/cache alone is insufficient |
| P16 multi-pass split | [`P16_MULTIPASS_SPLIT.md`](P16_MULTIPASS_SPLIT.md) | Alpha 36 pass boundaries, shared-SSBO synchronization, independent reflection readiness, shader compile policy and fallback semantics |
| Persistent Vulkan pipeline cache | [`PERSISTENT_VULKAN_PIPELINE_CACHE.md`](PERSISTENT_VULKAN_PIPELINE_CACHE.md) | Alpha 37 startup measurements, Alpha 38 VkPipelineCache persistence, cache identity/fallback rules and runtime validation |
| P17 dynamic entities | [`P17_DYNAMIC_ENTITIES.md`](P17_DYNAMIC_ENTITIES.md) | Player/LivingEntity renderer capture, section/global broad phase, generic material ABI, upload optimizations, runtime coverage and remaining renderer families |
| P18 resource-pack / LabPBR | [`P18_RESOURCE_PACK_LABPBR.md`](P18_RESOURCE_PACK_LABPBR.md) | renderer-resolved texture identity, LabPBR decode/shading, animated PBR textures, alpha coverage and P18D fidelity follow-ups |
| Renderer settings / render profiles | [`RENDERER_SETTINGS.md`](RENDERER_SETTINGS.md) | Alpha 59 Video Settings integration, Quality & Performance render profile UI, current quality ranges and runtime ABI |
| Server gameplay-light phases | [`GAMEPLAY_LIGHTING_ROADMAP.md`](GAMEPLAY_LIGHTING_ROADMAP.md) | GL0–GL5 implementation/validation roadmap |
| Authoritative gameplay-light design | [`SERVER_GAMEPLAY_LIGHTING.md`](SERVER_GAMEPLAY_LIGHTING.md) | storage, propagation, spawn policy, sky/environment, budgets, resource estimates, validation matrix |
| Data-pack block lighting | [`LIGHTING_WORLD_RULES.md`](LIGHTING_WORLD_RULES.md) | `emission_color`, `gameplay_strength`, reload/sync semantics |
| Vulkan interoperability | [`VULKAN_INTEROP.md`](VULKAN_INTEROP.md) | Minecraft Vulkan ownership/interoperability constraints |

## Alpha 59 status snapshot

- Direct Totem world takeover is the accepted presentation architecture; vanilla level drawing is skipped after a complete Totem frame is ready.
- Fullscreen internal RT work uses bounded pixel budgets rather than an unbounded percentage of the desktop framebuffer.
- Current user-facing quality tuning: GI 1/2/3 samples, shadows 1/2/4 samples, ray distance 32/64/96/128 blocks (64 default), internal resolution LOW/BALANCED/HIGH with per-preset pixel ceilings.
- Video Settings uses a persistent render profile (Minecraft / Totem Lumen). Alpha 59 places the selector and Totem controls directly in the native Quality & Performance section.
- P17 Player/LivingEntity core geometry is active; spider-eye emissive material behavior is runtime-validated. Non-Model entity families and full material fidelity remain.
- P18A/B/C are implemented; P18D and broader runtime acceptance remain.
- GL4 server-light profiling remains the next gameplay-light validation gate.

## Current accepted client-lighting decisions

| Topic | Accepted direction |
| --- | --- |
| Overworld sun source | captured Overworld clock drives one procedural sun direction and sun disk in P13 |
| Overworld moon source | moon direction is exactly the celestial opposite of the P13 sun direction and is horizon-gated independently |
| Moon sky appearance | procedural disk plus low-intensity halo; no separate sky texture/resource/pass |
| Moon surface lighting | weak cool directional term with the same ray-traced visibility semantics as sun lighting |
| Lunar phase | Alpha 40 captures Minecraft 26.2's resolved eight-step `MOON_PHASE`; 3 packed environment bits drive moon silhouette, halo and directional moonlight strength while preserving the 16-bit stochastic seed |
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
| Out-of-cell BE geometry | P17's instance-AABB / section-broad-phase work is the preferred reusable basis for a later out-of-cell block-entity extension; owner-voxel DDA alone is insufficient |
| Fluid geometry | P14E exact flowing/sloped water/lava geometry is implemented with Fluid ABI v2 and shared nearest-hit integration; detailed runtime geometry/optics validation remains |
| Alpha-cutout geometry | P18C implements zero-alpha ray-visible holes and stochastic partial-alpha coverage for generic textured geometry; entity/block-entity alpha fidelity remains follow-up work |
| Out-of-cell / random-offset static models | require instance/broad-phase follow-up; do not destroy mesh dedup by baking position into every mesh id |
| Dynamic entity source | capture renderer-resolved Minecraft 26.2 `Model` submissions inside an `EntityRenderDispatcher.submit(...)` scope; do not maintain mob-specific ray-geometry tables |
| Alpha 42 entity scope | Player and ordinary `LivingEntity` renderers first; items, vehicles, projectiles, text/display and custom command families require explicit follow-up adapters |
| Dynamic entity ownership | retained scene data is immutable/copy-owned: entity-local quad positions + absolute world position + world AABB; no live `Entity`, render-state, `Model`, `ModelPart` or `PoseStack` enters Vulkan code |
| Dynamic entity precision | absolute CPU world position/AABB remains double precision; the future GPU ABI should encode section-relative values rather than prematurely converting far-world coordinates to float |
| Dynamic entity broad phase | bin each world AABB into overlapping 16x16x16 sections; rays query bounded section candidates instead of linearly scanning every entity |
| Dynamic entity overflow | per-section candidate capacity is bounded and overflow is counted/reported explicitly; capacity changes require runtime measurements |
| Dynamic entity GPU updates | P17 uses an isolated dynamic tail; pose-only updates preserve material textures and Alpha 49 restored one contiguous MoltenVK-friendly upload after the Alpha 48 split-copy regression |
| Dynamic entity consumers | one shared nearest-hit path must feed camera rays, sun/moon and local-light visibility, diffuse GI, sky/environment visibility, P15 transmission where applicable and P16 reflection |
| Scene-quality milestone order | Alpha 59 baseline -> remaining P14D/P14E/P17/P18 coverage/runtime gates -> primary-hit/G-buffer performance architecture -> P19+ advanced rendering |
| Reflection baseline | one bounded secondary reflection ray in GI Composite |
| Roughness | 4-bit full-cube fallback profile; deterministic rough reflection direction |
| Metallic | 4-bit full-cube fallback profile; metallic F0 tint |
| Fresnel | Schlick approximation |
| Reflection distance | 64-block cap for P16 baseline |
| Reflection through glass | reuse P15 filtered/transmissive trace along the secondary ray |
| Glass interface reflection | deferred until refraction/Fresnel interface transport |
| Specular recursion | excluded from P16 |
| Surface source of truth | renderer-resolved texture identity plus P18 LabPBR 1.3 data when available; built-in/material-rule fallback remains for missing PBR channels |
| Voxel memory growth | none for per-voxel records; P14C/P14D share the scene mesh pool and P17 dynamic entities use a separate bounded scene region rather than widening voxels |
| Base compute pipeline | unified P12-P15 + P14E + P17 + P18 full-lighting pipeline; renderer readiness remains independent of optional P16 reflection compilation |
| Reflection compute pipeline | independent P16 pass over the same scene SSBO; currently retraces the primary ray and is a target for future primary-hit/G-buffer reuse |
| Pass synchronization | compute shader-write -> shader-read/write SSBO barrier between base and reflection dispatches |
| Base GLSL -> SPIR-V | background shaderc prewarm at O0; current production source is verified in CI |
| Reflection GLSL -> SPIR-V | dedicated worker at O0; current production source is verified in CI |
| P16 shaderc performance optimization | rejected for current split source: produced 1,515,084-byte SPIR-V and about 20 s CI compile time |
| SPIR-V -> driver pipeline | background Vulkan pipeline work; never block the Minecraft render thread |
| Reflection failure policy | keep P12-P15 renderer active; disable only P16 reflection for that resource generation |
| MoltenVK/Vulkan pipeline cache | Alpha 38 persists driver/device-keyed opaque `VkPipelineCache` data; Apple M4 cache-hit runtime measured base 49 ms and P16 73 ms; cache remains an optimization, never a correctness dependency |
| Future shader growth | prefer smaller bounded passes and reusable primary-hit/material data; whole-mega-shader Osize optimization was rejected after SPIR-V ID overflow |
| Optimization policy | preserve the Alpha 51+ direct world-takeover baseline, measure Apple/MoltenVK runtime cost, avoid split micro-copies that regress MoltenVK, and optimize confirmed hot paths rather than quality-blind ray reductions |

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
