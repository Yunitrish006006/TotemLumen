# Lumen Profile Performance Experiment Plan

Status: **planned; no renderer changes have been made for this plan**. This plan is scoped to the `TOTEM_LUMEN` client renderer. It does not change `MINECRAFT_RGB`, dedicated-server gameplay lighting, or spawning rules. Vulkan compute ray tracing remains the supported baseline; a rasterized-primary experiment is conditional, not a replacement decision.

## Alpha 60 evidence that motivates the work

The 2026-09-26 Minecraft 26.3 / Apple M4 / MoltenVK 1.4.2 run used Totem Lumen `0.1.0-alpha.60`. These are existing-pipeline measurements from `logs/latest.log`, not measurements of a G-buffer or rasterized implementation:

| Configuration and mode | Observed submit-to-complete time | Interpretation |
| --- | --- | --- |
| 512×288, GI Low, Shadow Low, 32-block primary distance, reflections on, GI Composite | 204.180 ms (one 30-frame sample window) | Reflection is a likely major cost; repeat before treating the ratio as a controlled result. |
| Same configuration, reflections off | 56.071 and 56.844 ms (two sample windows) | Strong directional evidence for isolating P16. |
| 427×240, reflections off, F9 Normal | 13.866–20.698 ms in a later set of four windows | Approximate primary-visibility/control baseline, not GPU-only time. |
| 427×240, reflections off, F9 Indirect GI | 48.862–53.454 ms across five windows | Secondary GI work needs separate measurement. |
| 427×240, reflections off, F9 GI Composite | 63.465–64.238 ms across two windows | Includes more than primary visibility; modes are not additive timing slices. |

The same run recorded a 128/128-resident section lookup with `lookupMaxProbe=89`, a P14 model-mesh warning at 65,527/65,536 quads followed by a 65,535-quad GPU registry, and one P16 binding failure after its previous scene buffer had closed. Base Vulkan driver pipeline creation took approximately 24.2 minutes; P16 creation took approximately 5.3 minutes. Startup time, visual capacity, lifecycle correctness, and steady-state frame time must be tracked separately.

`avgSubmitToCompleteMs` is a fence/completion interval, **not** an isolated GPU timestamp or the game's displayed FPS. Changing resolution, world content, chunk loading, resource packs, or quality settings invalidates direct A/B comparison. The currently available reflection-on observation is only one sample window.

## Execution plan and decision gates

| Stage | Work | Evidence and exit gate |
| --- | --- | --- |
| LP-0 — reproducible baseline | Add or use per-pass GPU timestamps where the running Vulkan device supports them; retain labeled submit-to-complete and CPU pack/upload measurements. Record device/driver, source and JAR hashes, viewport/internal extent, scene, resource packs, ray/quality settings, and in-flight scene changes. Run repeated A/B/A windows after warm-up in a stationary, fully loaded world. | Report per-pass and end-to-end median/p95 frame times with sample counts. Existing F9 modes remain useful diagnostics but are not interpreted as additive GPU costs. |
| LP-1 — correctness and lookup | Fix the closed-scene-buffer P16 bind lifecycle. Measure section-lookup occupancy, successful and missing probe lengths; test a lower-load-factor lookup table without changing the number of resident sections or the 32-bit voxel record. | World exit/re-entry, resize, settings changes, and recompile have no stale-buffer error. Negative coordinates, collisions, misses, and full resident-window correctness pass. Same-scene A/B shows whether the lookup change actually helps. |
| LP-2 — primary-hit reuse | Keep the current compute path selectable. Prototype a bounded current-frame primary-hit/G-buffer record produced by the full base pass and consumed by P16, so P16 can avoid a second full camera ray where the record is valid. Include hit kind/identity, depth, material/surface coordinates, normal, and transmission information as required by existing P14E/P15/P17/P18 semantics. Synchronize writes/reads through the existing Vulkan submission path. | Shader compilation and ABI checks pass; opaque blocks, alpha cutouts, glass, exact water/lava, moving entities, animated PBR, scene edits, and resize are visually compared against the old path. Adopt only if repeated same-scene end-to-end and P16 timings improve beyond measurement noise without correctness regressions. |
| LP-3 — GI follow-up | With LP-2 measured, isolate secondary-GI tracing, visibility, and material-resolution costs. Test one bounded optimization at a time while preserving the existing one-bounce GI quality contract. | Repeated same-scene timing improves without unacceptable light leaks, ghosting, or material/texture mismatches. Reject changes that only shift cost to uploads or compilation. |
| LP-4 — conditional rasterized-primary prototype | Only if LP-1–LP-3 do not meet the desired performance/quality balance, prototype rasterizing a small representative block-model scene to primary-hit data while retaining Vulkan compute secondary rays. Do not replace the production renderer during this experiment. | Compare like-for-like frame time, memory, update cost, compile/startup cost, and rendered screenshots on Apple Silicon/MoltenVK and a native Vulkan desktop GPU. Continue only for a repeatable net benefit with viable transparent/fluid/entity behavior. |

Two parallel acceptance tracks remain visible rather than being misclassified as GPU speedups:

- **Geometry capacity:** investigate the P14 mesh-quad fallback and representative resource-pack/world coverage; preserve visible model fidelity while changing any capacity or residency policy.
- **Startup:** investigate why an SPIR-V cache hit still led to a roughly 24-minute base driver pipeline creation in this run, and measure cold/warm launches separately from in-world frame time. Never block Minecraft's render thread while compiling.

Each stage is independently reviewable and should produce a test JAR, a source/JAR identity, matched runtime logs, and visual evidence when appearance changes. Do not claim an optimization from compilation alone. Preserve Minecraft's world-render fallback until a complete Totem frame is ready.

## First next step

Start with LP-0 and LP-1. The full section lookup and P16 lifecycle error affect the reliability of later A/B results. Only then compare a primary-hit reuse prototype with the unchanged compute baseline. The roadmap's broader P14D/P14E/P17/P18 coverage and server-light milestones remain separate work.
