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

## LP-1 follow-up — 2026-10-01

The local follow-up starts from `f4180149ca566ab44b91b7b1060505903c901ca8`.
Independent review found additional lifetime gaps around allocation failure,
shutdown and background cleanup. The working-tree patch:

- Detaches P12/P16 before scene storage is destroyed when resource creation fails.
- Invalidates P12 workers and shuts down P16 under the same P12 → P16 lock order.
  The post-prewarm reflection continuation also checks the current generation.
- Removes background `vkDeviceWaitIdle` cleanup. Descriptor pools retire through
  Minecraft's Vulkan destruction queue on the render thread; worker requests are
  drained each client tick and at client stopping.
- Retains shared pipeline objects until every bound descriptor has completed
  GPU-safe retirement. Closing an unbound prepared pipeline remains immediate.
  Prepared pipelines remain reusable across ordinary scene detachment.

Java 25 / Gradle 9.6.1 `--offline build --no-daemon` passed: 152 tests in 45
suites, including nine new lifetime tests, plus the existing shader and mixin
verification tasks. The new tests cover owner retirement before outstanding
GPU-completion callbacks, world re-entry, failed-binding release, rejection of
new leases after retirement and concurrent duplicate completion. They do not
simulate Vulkan queue timing. Independent static re-review found no new
actionable regression in this patch.

Native runtime uses an isolated Minecraft 26.3 / Fabric Loader 0.19.5 client,
Linux x86-64, RTX 5060 Ti, NVIDIA 595.84, Vulkan 1.4.329, and an Xvfb display.
The client entered a new creative flat test world (`LumenLP1Runtime`, seed
20261001, structures disabled). Bootstrap shader/pipeline initialization passed.
Full lighting remained in driver pipeline compilation during the initial
854×480 → 1024×576 resize and world save/exit. Minecraft reported a transient
swapchain-out-of-date presentation error at resize and recovered at the new
size. No scene-binding failure was observed in this window, but no full-lighting
binding or world-takeover event had occurred: **this is not completed LP-1
runtime acceptance or a performance measurement**.

The user will perform Apple Silicon/MoltenVK validation separately. With the
patched build, use a copy/test world and wait for all of:

1. `Full lighting pipeline prewarm COMPLETE` and `Full lighting scene binding READY`.
2. `Full lighting renderer READY` and `Totem Lumen WORLD TAKEOVER active`.
3. `Reflection scene binding READY`, followed by an actual reflection dispatch.

Then resize down/up, switch reflections off/on, save and exit the world, and
re-enter it. Capture the resulting frames and log. Each recreated scene must
reach base/reflection readiness again without closed-buffer, binding, retirement
or Vulkan errors; verify that the world image and reflection toggle recover.
Compilation progress or a normal Minecraft image alone is insufficient.

The initial native run then re-entered the world successfully while compilation
continued. Saving and quitting the application at 13:54:27 reproduced SIGSEGV
in `TotemLumen-P12FullPipeline` inside `vkCreateComputePipelines`; the full
pipeline had started at 13:46:00 and never reached readiness. This is a failed
shutdown result, not an accepted runtime run.

The subsequent fix adds a per-device worker lease reserved before thread start.
It spans native creation, publication and failed/stale cleanup for bootstrap,
P12/P16 preparation and binding. A minimal `VulkanDevice.close()` HEAD mixin
rejects new work, invalidates binding waiters, waits for outstanding leases,
retires bootstrap ownership, drains descriptor retirement and closes the cache
before Minecraft destroys the device. Interrupts cannot bypass this wait. This
can delay **final application exit** until an uninterruptible driver compilation
returns; frame rendering, resize and world exit do not wait on this gate.
Four regression tests cover outstanding native work, interrupted shutdown,
leases reserved before thread startup, duplicate completion and admission closure.
Independent re-review found no blocking code issue for the current single-device
lifecycle. A repeat cold-compile shutdown with a clean native exit and warm
shutdown with an in-flight dispatch remain required runtime gates.

The final native integration smoke used Minecraft's Vulkan backend with the
local `MINECRAFT_PURE` profile (no Totem compiler workers). Normal Quit Game
logged `Vulkan device workers quiesced; backend destruction is safe` and
`runClient` completed successfully. This proves mixin installation and the
zero-worker close path, not the outstanding-worker runtime cases above. The
temporary profile override was removed after exit. The test client is stopped.

Development JAR: `build/libs/totem-lumen-0.1.0-alpha.61.jar`.
SHA-512:
`9af3a720466b4cb689f458989689ae78dd85d242e3d5fd554f46cab5c26fe9e458419bb3b50030d501dd1866dee634d9687ceaf6f5cd8e9bc24c461e3cecb8a1`.

This patch does not establish full-lighting visual acceptance, P17 coverage,
P14D/P14E/P18 acceptance or LP-0 performance measurement. Local evidence remains
under ignored `build/reports/lp1/`; the test JAR retains the Alpha 61 version and
is an unpublished development build, not the published Alpha 61 artifact.

## LP-1 isolated worker probe — 2026-10-03

[The reproducible shutdown probe](LP1_WORKER_SHUTDOWN_PROBE.md) now covers
the production worker gate with real native creation in an isolated process.
Four RTX 5060 Ti runs passed: bootstrap with empty application cache and
reflection with an imported research seed, each with ordinary and interrupted
shutdown. A deterministic bypass negative control rejects an early gate return.
The worker is pre-reserved and enters native creation only after shutdown is
observed waiting. These results establish the isolated gate's ordering; the
Minecraft close mixin, in-flight dispatch, full-lighting lifecycle and Mac gates
above remain open. The probe does not change production shaders or renderer code.

## Pipeline cache file reliability — 2026-10-03

The production cache store now uses bounded same-handle reads and unique,
forced staging files with atomic replacement; failed replacement keeps the old
cache, and rejected data is not deleted through a possibly replaced pathname.
See [cache reliability evidence](PERSISTENT_VULKAN_PIPELINE_CACHE.md) for scope,
limitations and report paths. Final build passed 159 tests (seven added), shader
and mixin checks. Two final isolated RTX 5060 Ti probes round-tripped a 21,980-byte
cache and confirmed application-cache hit on import. Dedicated-server entrypoint
loading reached the expected EULA gate, not world startup. Full Minecraft/Mac
runtime acceptance remains open; no startup/FPS improvement is claimed.

## Pipeline creation exceptional completion — 2026-10-03

`VulkanPipelineCacheStore` previously released `activeCreates` only after the
diagnostics call and logging returned normally. A Java exception could leave
the cache permanently busy, blocking subsequent persistence or leaving deferred
cache destruction pending. Creation now uses a `finally` completion path;
only admitted cache users decrement the count, success marks the cache dirty,
and completion always notifies monitor waiters. An original compilation
exception is preserved if cleanup also throws (the latter is suppressed).
Creation logging follows reservation release, so a logging failure cannot
strand the count either. No shaders, cache file format, gameplay rules or
cross-module contracts changed.

`verifyPipelineCacheFailure` is now a dependency of Gradle `check`. It runs in
an isolated JVM against the production completion method, injecting seven
scenario categories: RuntimeException, Error, Vulkan error return, successful
creation, uncached work, overlapping reservations/deferred close, and monitor
notification. Reflection seeds bookkeeping with a zero native handle: the
verifier never creates or destroys GPU objects and is not native lifecycle
acceptance. Research/verifier classes remain excluded from the player JAR.

Java 25 / Gradle 9.6.1 `--offline build prepareCompileResearch
verifyWorkerShutdownResearch` passed: 159 unit tests were executed with zero
failures/errors, the new seven-category verifier passed, the existing shutdown
gate verifier passed, and shader/mixin/RGB checks passed. The documented local
Gradle installation was used because this repository has no wrapper.
The dedicated-server entrypoint smoke from the preceding file-I/O change is
not a new runtime result; common/server code was untouched here. Native Vulkan
in-world failure/close/re-entry, Apple Silicon/MoltenVK and full-lighting/FPS
acceptance remain open. The change is local and unpublished.

## Opt-in candidate runtime integration — 2026-10-03

The CR2 shared-filtered-trace candidate is now wired into actual P12/P16 source
construction behind a process-fixed opt-in. Baseline remains the default.
See [candidate runtime instructions and evidence](SHADER_CANDIDATE_RUNTIME.md).
Both variants compile in `check`; golden hashes match the measured research
sources exactly. Build and 159 unit tests passed. A real Minecraft Vulkan client
selected the candidate full shader; full/reflection readiness, image acceptance,
Mac validation and independent integration review remain separate gates.

Runtime follow-up: both passes completed and rendered in the disposable world
on RTX 5060 Ti. First actual-game application-empty prewarm: full 746,940 ms,
reflection 121,289 ms; same-directory warm restart: full 2,568 ms, reflection
660 ms (not total game startup). Warm re-entry and resize rebound both passes.
The corrected first run exited 0 with worker-quiesced evidence; warm shutdown
saved the world/closed cache but launcher returned 143, cause unknown.
Blue-grey/noisy ground requires matched baseline comparison. These findings do
not authorize default adoption; see the linked runtime document for all limits.

## Block/biome tint follow-up — 2026-10-03

Static quad extraction and the P18 material sampler previously dropped Minecraft
block tint in both shader variants. The client now snapshots the tint source's
world-resolved RGB, retains it through cube UV canonicalization/registry dedupe,
and packs it into the cube and mesh GPU tables. Full lighting and reflection
share the tinted albedo evaluation. Negative/missing tint indices stay neutral;
alpha, server RGB rules, quality controls and default variant selection do not change.

The internal material layout changed deliberately (cube surface ABI v2, ten words
per face; 22 words per model quad). Old CR2/prewarm timings must not be relabeled
as results for the new sources. Offline build passed in 54 s with 163 unit tests,
new production packing/canonicalization checks and both shader variants checked.
The isolated dedicated-server load reached the expected unaccepted-EULA gate
and exited successfully. The actual client captured grass top tint `0x91bd59`.
Native full/reflection prewarms completed in 741,512/120,078 ms; both bound and
rendered, with green grass visible. Sky/brightness/noise and broader image
acceptance remain open. Save and Quit followed by Quit Game exited 0 with
worker-quiescence evidence. This does not resolve the older exit-143 incident.

The tint revision's same-cache restart measured 684 ms full + 375 ms reflection
prewarm (not total startup). World re-entry and a confirmed 1024x576 resize
rebound both passes; the second Save and Quit / Quit Game also exited 0. Both
test game processes are closed. Independent review still awaits authorization.

See `SHADER_CANDIDATE_RUNTIME.md` for source hashes, test scope and remaining
visual, capacity, MoltenVK and independent-review gates. No release was made.
