# Totem Lumen profile handoff — 2026-09-30

## Scope and current state

This handoff concerns the `TOTEM_LUMEN` Vulkan compute profile on Minecraft
26.3 / Java 25 / Fabric Loader 0.19.5. Minecraft RGB's gameplay and visual
acceptance is recorded separately in the September 28–30 RGB documents.
`TOTEM_LUMEN` still has renderer coverage, visual validation, lifecycle and
performance work; do not infer that those gates passed from RGB acceptance.

The current renderer path is Minecraft extraction → immutable scene snapshots
→ bounded GPU scene (static voxels, P14 meshes, P14E fluids, P17 entities,
P18 materials) → shared compute primary/lighting → optional P16 reflection
→ temporal/denoise → Minecraft presentation. The server gameplay-light field
is authoritative for gameplay and is independent of this Vulkan renderer.
See `ARCHITECTURE.md` and `LUMEN_PROFILE_PERFORMANCE_PLAN.md` before changing
the renderer.

## Work completed in this handoff

- Inspected `ROADMAP.md`, the P14D/P14E/P17/P18 documents, the performance
  plan, implementation and verification entry points. The repository was clean
  before this work.
- Switched the local 26.3 development client to `TOTEM_LUMEN` and entered the
  isolated `CodexRgbRuntimeCopy` world. The runtime log confirmed profile
  selection and `P17 dynamic entity geometry capture active:
  type=minecraft:zombie quads=42`.
- The full P12/P18/P17 lighting pipeline remained in background SPIR-V/Vulkan
  driver compilation for about ten minutes. Minecraft kept presenting its
  normal renderer. No `Full lighting pipeline prewarm COMPLETE`, P12 scene
  binding, P16 scene binding or Totem world-takeover event was observed in
  this run. The client then shut down normally and saved the test world.
  **This run cannot establish Lumen-profile visual correctness or performance.**
- Fixed an identified scene-buffer lifetime race: when the renderer destroys
  its old resources, it now detaches P12 and P16 scene descriptors before
  closing the scene buffer. Background descriptor binding and detach use the
  same lock, and the prepared pipelines remain available for a new scene.
  This targets the earlier P16 binding-to-closed-buffer symptom described in
  the performance plan. The first build ran while the earlier client still
  had the previous classes loaded; that client did not hot-update. The new
  code still needs an in-world resize/exit/rejoin test. Future rebuilds should
  follow the README and stop `runClient` first.
- Gradle 9.6.1 `--offline check` passed after the lifecycle change, including
  Java tests, mixin descriptor gates and bootstrap/full/P16 shader compilation.
  This is a build result, not a runtime proof of the race fix.

## Remaining work, in order

1. **LP-1 runtime lifecycle:** launch the patched client, wait for full P12
   pipeline readiness, then resize the window, exit/rejoin the test world and
   toggle reflections. Confirm no closed scene-buffer binding failure, no stale
   reflection descriptors and successful P12/P16 reattachment. Keep source,
   build, runtime log and observed frame evidence separate.
2. **P17 renderer-family gate:** with `TOTEM_LUMEN` actually presenting, test
   a dropped stone, a flat item and a block-shaped item; a boat or minecart;
   and two projectile types. Check nonzero P17 capture/GPU counts, visible
   ray-traced geometry, despawn/rejoin cleanup and no vanilla regression.
   Broader armor, leashes/flames, display/text/custom renderers and first-person
   hand/held-item coverage remain implementation work.
3. **P14E/P14D/P18 visual gates:** validate exact fluid slopes, waterfalls,
   waterlogged coexistence, live updates, water transmission/reflection and
   lava emission; bell/chest animation and other block-entity command families;
   and LabPBR resource reload, material response and alpha cutouts. The
   current documents list the detailed cases. Compile success alone does not
   close these gates.
4. **LP-0/LP-1 performance:** capture repeatable stationary A/B/A Lumen
   timings with resolution, scene and settings fixed, then measure section
   lookup occupancy and both hit/miss probe lengths. The CPU/GPU lookup has
   128 buckets for up to 128 resident sections, so the previous
   `lookupMaxProbe=89` observation warrants a lower-load-factor experiment.
   Changing lookup capacity shifts GPU scene offsets and must update both
   full-base and P16 shader constants/ABI checks. Do not claim a speedup from
   the capacity change without a matched runtime measurement.
5. **Temporal and later quality work:** replace global history invalidation
   for moving entities with per-hit identity; then pursue primary-hit reuse,
   secondary GI and remaining P18D material fidelity with visual gates.

## Local continuation

The local 26.3 test configuration selects `TOTEM_LUMEN`; it is ignored by Git
and is not a published default change. The development client was closed after
saving. The repository has no checked-in Gradle wrapper binary; use local
Gradle 9.6.1 and its documented tasks from the repository root. The next run
should observe the log until the full pipeline is ready, and use only a copy/test
world for placement or movement probes. No test world, JAR, local path or
credential belongs in this repository.

Relevant source entry points: `P5StableLookupRenderer`, `P12FullBasePipeline`,
`P16MultipassReflection`, `VulkanComputeProgram`,
`EntityRenderGeometryCapture`, `P17DynamicEntityGpuUploader`, and the
P14E/P18 shader patches under `src/client/java/dev/totem/lumen/`.
