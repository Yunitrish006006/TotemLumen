# Performance-first renderer reference study

Date: 2026-10-11

This study answers a narrow question before further U2 implementation: how do established Minecraft renderers and shader packs keep visual effects affordable, and which ideas apply to Totem Lumen's Vulkan/MoltenVK renderer? It is architecture evidence, not an FPS comparison. Project claims below come from each project's own documentation or source; recommendations for Totem Lumen are explicitly marked as conclusions.

## What other renderers actually do

| Project | Primary rendering strategy | Performance controls that matter | Applicable lesson |
| --- | --- | --- | --- |
| Sodium | Retained chunk sections, region-managed GPU resources and draw lists, with graph/frustum/occlusion/fog visibility before submission. Current source also performs asynchronous culling, rejects stale camera-local results, prioritizes nearby rebuilds and budgets uploads from measured frame duration. | Early face, fog, section and entity culling; update only visible animated textures; bounded frames in flight; separate important/deferred chunk work. | Keep scene residency stable and visibility-driven. Rebuild or upload only the changed part, and never allow an old camera result to become current. |
| Iris shader pipeline | Rasterizes geometry into `gbuffers`/shadow passes, then runs deferred/composite/compute work over textures and buffers. It supports smaller intermediate buffers and retains back-face, frustum, occlusion and shadow culling. | Pass-specific resolution, culling and bounded shadow scope; expensive effects operate on already established primary visibility. | A performance-first lighting renderer should reuse primary depth/material/normal data. Secondary lighting does not need to rediscover every primary hit. |
| Complementary Reimagined | Builds quality profiles from independent feature budgets. Its public properties scale shadow distance, reflections, clouds, detail and colored-light voxel volumes separately. | Reflections can be half resolution; shadow distance is tiered; costly entity shadows and world-space reflections are disabled in lower profiles; colored-light volume size is bounded by profile. | Quality profiles should alter the expensive subsystem that dominates cost, rather than globally reducing fidelity without diagnosis. Spatial volume and ray effects need separate budgets. |
| VulkanMod | Replaces the OpenGL renderer with Vulkan and identifies reduced CPU/GPU overhead, optimized terrain draw loops, section culling, chunk building and entity optimization as its main gains. | Remove submission overhead and reject sections before drawing. | Vulkan alone is not the optimization. Retained data, batched submission and culling decide whether the API advantage survives. |
| Nvidium | Replaces Sodium's terrain backend and uses NVIDIA-specific features to draw very large terrain sets. It requires Turing-or-newer NVIDIA hardware. | GPU-driven/vendor-specific terrain path. | GPU-driven draw/compaction ideas are useful, but a required Nvidium-style feature set is incompatible with Totem Lumen's MoltenVK and portable Vulkan baseline. Such acceleration must remain capability-gated. |
| Distant Horizons | Renders normal nearby terrain and simplified LOD terrain outside the normal render distance. Recent official release notes describe a quadtree, player-centered update priority, behind-camera culling, automatic overlap/fading and reduced node-update work. | Distance hierarchy, coarse far representation, camera-relative priority and controlled transition between representations. | Do not expand the same full-resolution voxel representation indefinitely. Use a near exact set and a coarse far representation with a stable overlap/fade policy. |
| Minecraft PTGI reference pack | Uses a hybrid raster pipeline, shadow-pass voxelization into a bounded uniform grid, fullscreen path-traced GI, SVGF on an albedo-free GI signal, motion buffers/reprojection and final TAA. Its author documents flicker at 1 SPP/2 bounces and much higher cost at 6 SPP/4 bounces. | Denoise a separated lighting signal over space and time; keep the voxel domain bounded; expose sample/bounce tradeoffs. | Temporal reconstruction is necessary for low-ray GI, but it cannot repair unstable geometry identity, distant aliasing or missing motion/disocclusion data. |

## Primary-source evidence

- [Sodium `RenderSectionManager`](https://github.com/CaffeineMC/sodium/blob/dev/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager.java) owns persistent section/region state, asynchronous culling, camera-result validation, render lists, measured upload budgets and nearby task priority. [Sodium's option descriptions](https://github.com/CaffeineMC/sodium/blob/dev/common/src/main/resources/assets/sodium/lang/en_us.json) document face/fog/entity culling, visible-only texture animation, bounded render-ahead and persistent mapping.
- [Sodium `OcclusionCuller`](https://github.com/CaffeineMC/sodium/blob/dev/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/occlusion/OcclusionCuller.java) traverses connected sections with distance/frustum/visibility tests and uses a consistent near-to-far diamond order instead of sorting an unordered set every frame.
- [Iris program-order documentation](https://github.com/IrisShaders/docs/blob/main/src/content/docs/current/Reference/Programs/overview.mdx) separates geometry passes from deferred/composite/compute passes. [Iris rendering properties](https://github.com/IrisShaders/docs/blob/main/src/content/docs/current/Reference/Shaders.Properties/rendering.mdx) describe intermediate-buffer sizing and back-face, frustum, occlusion and shadow culling.
- [Complementary's public profiles](https://github.com/ComplementaryDevelopment/ComplementaryReimagined/blob/main/shaders/shaders.properties) independently bound shadow distance, reflection quality and colored-light volumes. [Its option descriptions](https://github.com/ComplementaryDevelopment/ComplementaryReimagined/blob/main/shaders/lang/en_US.lang) expose half-resolution reflections and warn that entity shadows can dominate frame cost.
- [VulkanMod's official repository](https://github.com/xCollateral/VulkanMod) lists CPU/GPU overhead reduction, section culling, draw-loop, chunk-build and entity optimizations as its renderer features.
- [Nvidium's official repository](https://github.com/MCRcortex/nvidium) describes a high-throughput alternate Sodium backend and its Turing-or-newer NVIDIA requirement.
- [Distant Horizons' official repository](https://gitlab.com/distant-horizons-team/distant-horizons) defines its near/full plus far/LOD split. [Official release notes](https://gitlab.com/distant-horizons-team/distant-horizons/-/releases) record quadtree replacement, player-centered update order, camera culling and transition work.
- [Minecraft PTGI's official repository](https://github.com/MahoganyTown/Minecraft-PTGI) documents its bounded voxel grid, separated GI signal, SVGF, temporal reprojection, TAA and the quality/performance limits of low samples and distant geometry.

## Totem Lumen comparison

The current P5 renderer has two coupled capacity limits:

- `MAX_SECTIONS = 128` controls the exact 16x16x16 voxel residents.
- `LOOKUP_CAPACITY = 128` uses an open-addressed table for the same residents.

At the already observed `128/128` resident state, the lookup load factor reaches 1.0 and `lookupMaxProbe` reached 84. Increasing the resident count alone would increase exact voxel storage by 16 KiB per section before light/material tails, while merely separating lookup capacity from resident capacity is cheap: moving from 128 to 256 four-word buckets adds only 2 KiB. This makes lookup headroom a low-risk first correction, but it does not solve world coverage.

The larger visual problem is selection stability. `nearestSections()` copies values from an unordered scene collection, sorts only by floating-point distance and truncates at 128. It has no complete key tie-break, visibility priority, hysteresis or representation for rejected sections. A resident-set change then requests a full upload, and full upload disables temporal history reuse. The observed result can therefore combine visible section replacement with a full-frame noise reset.

Simply raising `MAX_SECTIONS` is not the performance-first answer. It increases exact geometry residency and ray traversal domain without addressing selection churn, far distance quality or per-pass cost.

## Decisions for the implementation plan

These are conclusions from the comparison and become the order for subsequent microtasks:

1. **Stabilize exact residency first.** Select sections with a deterministic total order, reserve capacity for currently resident sections near the cutoff, and prioritize camera/frustum relevance. Movement inside a small hysteresis band must not reshuffle residents.
2. **Decouple hash capacity from resident capacity.** Keep the open-addressed lookup below a measured load-factor ceiling. Record load factor, maximum probe count, misses and resident replacements.
3. **Stop invalidating the whole temporal image for local scene changes.** Carry section/slot revision and screen-space disocclusion information so only affected pixels or tiles reject history. This follows after stable selection because dirty-region history cannot be correct while identity churns.
4. **Reuse primary visibility.** Preserve the Vulkan compute baseline, but cache primary hit depth, normal, material, alpha/coverage and motion identity once. Shadows, GI and reflections consume that data when their semantics allow it. P16 must not permanently retrace the same primary ray.
5. **Separate expensive signals and resolutions.** Direct lighting, GI, reflections, transparency and final composition get independent budgets. GI/reflections may use reduced internal resolution plus reconstruction; UI and stable edges remain at presentation resolution.
6. **Add a distance hierarchy.** Exact near voxels remain authoritative for ray hits. Far terrain should use a coarse clipmap/brick/LOD representation and a stable overlap transition, rather than unbounded exact sections or missing random chunks.
7. **Make acceleration capability-driven.** Indirect/mesh/hardware-RT paths may accelerate supported native Vulkan devices, while portable compute remains correct on MoltenVK.
8. **Measure frame time by pass before trading quality.** Required counters are CPU extraction/select/upload time, GPU primary/GI/reflection/resolve timestamps, resident churn, lookup probes, history rejection ratio and pixels traced per pass. Average FPS alone hides camera-turn spikes and residency churn.

## Revised bounded execution order

| Order | Microtask | Focused proof | Stop boundary |
| --- | --- | --- | --- |
| R0 | This reference study | Official-source findings mapped to current P5 evidence | Documentation only; no renderer/runtime change |
| R1 | Resident selection stability | Insertion-order independence, total tie-break, frustum priority and cutoff hysteresis tests | Do not change temporal/color paths |
| R2 | Lookup headroom and telemetry | Load-factor ceiling, collision/probe/miss tests and bounded memory delta | Do not increase exact resident count |
| R3 | Resident-change invalidation contract | Section revision to affected pixel/tile mapping; unchanged pixels retain history | Contract only before shader edits |
| R4 | Primary-hit reuse profiling | Per-pass timestamps prove retrace cost; one ABI proposal covers P12/P16 consumers | No raster fallback or quality reduction without evidence |
| R5 | Far representation study | Memory/ray-cost model and MoltenVK-compatible clipmap/brick prototype gate | No replacement of exact near geometry until visual transition is proven |

R1 remains the next implementation task. Native Vulkan visual/FPS validation follows the relevant implementation checkpoints and must reuse the single-client rule. Apple Silicon/MoltenVK, lifecycle and server RGB profiling gates remain unchanged.

## Limits of this study

The projects use different Minecraft versions, graphics APIs and quality targets, so their FPS numbers would not be comparable to Totem Lumen. Nvidium's vendor-specific design and the OpenGL-specific implementation details of Iris shader packs are reference patterns only. The recommendations above still require Totem Lumen's Java 25/Minecraft 26.3 build checks, native Vulkan measurements and Apple Silicon evidence.
