# Near ray lighting without clipping distant terrain

Status: experimental implementation, not default adoption or a release. Native Vulkan
and Apple Silicon/MoltenVK visual/lifecycle/performance acceptance remain required.

## User contract

At fixed Minecraft render distance, reducing the ray distance from 128 to 32 must
not remove far mountains/buildings. The ray budget limits near lighting, not terrain
availability. This work is owned entirely by TotemLumen; no Core/provider/network/
gameplay-light contract changes are needed.

## First implementation

Opt in at JVM startup with `-Dtotem.lumen.hybridTerrain=true`. The shader variant
is independent (`-Dtotem.lumen.shaderVariant=shared-filtered-trace` selects the
existing candidate). Both settings are process-fixed and require restart.

- Minecraft's native Vulkan world drawing is retained at its own render distance.
- Full GI compute exports a hit-distance plane beside its radiance plane. Raw IEEE
  float bits use RGBA8 byte transport and nearest texel reads; history alpha and P16
  radiance are not repurposed. Output storage/texture rows double only in this mode.
  Header capacity words 51/52 include both planes so every model/entity/fluid/PBR
  CPU packer and shader locates its data after the depth plane, not on top of it.
- A post-world, pre-hand/HUD fragment pass reconstructs the current raster surface
  from reverse-Z depth, reprojects it into the queued ray camera, and rejects sky,
  ray misses, out-of-bounds UVs and depth mismatches. Ray color/depth and metadata
  are associated with the same graphics-queue submission, not an older callback.
- The outer 20% of the near range fades to raster lighting. Missing ray geometry
  stays rasterized instead of being replaced by a sky/miss pixel. Compile/startup
  failure also keeps raster terrain. No world pixels are networked or captured for
  Observer; this is local GPU composition only.
- With the flag absent, takeover behavior, output layout and shader source remain
  the prior baseline. This avoids silently adopting an unvalidated renderer.

## Known gates / limitations

This is not a completed raster-primary/G-buffer lighting renderer or a performance
claim. It pays for both raster visibility and near compute. Transparent surfaces,
particles/weather/selection outlines, dynamic objects and low-resolution edges need
particular scrutiny: raster depth does not describe every blended contribution, so
post-world composition can overwrite a contribution that did not write depth. Until
those paths are validated/resolved this experiment must not become the default.
Depth correspondence alone is not a material/identity guarantee on moving surfaces.

Required scenes: fixed camera and moving camera; opaque landmarks beyond 32/64/128;
near wall occluding a far landmark; cutout leaves/grass; glass/water; particles;
entities; HUD/F1/hand; day/night; resize; settings changes; exit/re-entry and clean
shutdown. Compare native Vulkan and MoltenVK. Record viewport/internal extent,
ray distance, device, FPS/frame time definition, RSS/heap and VRAM. Do not infer FPS
from shader build success or call retained vanilla-only frames full-lighting success.

Build-time checks include both baseline/candidate hybrid compute sources, fragment
compilation, callback signature, output-plane layout/byte transport, invalid-hit
rejection and distance fade policy. These do not replace GPU visual validation.

## Local verification — 2026-10-03

- Java 25.0.3, Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.160.5+26.3.
  Repository has no wrapper; used the existing Gradle 9.6.1 wrapper distribution.
- Initial offline build passed in 2m49s with 169 tests. Subsequent layout inspection
  caught the missing second plane in header capacity word 52; that result alone
  was therefore not acceptance. The correction has an explicit regression check.
  Corrected offline build passed in 1m32s: 170 tests, zero failures/errors; both
  baseline/candidate hybrid compute sources, compositor and mixin checks passed.
- An isolated dedicated-server launch with the experiment property set reached
  the unaccepted-EULA gate and exited 0 (47s). No EULA accepted/world started;
  this is entrypoint isolation smoke, not full dedicated-server gameplay acceptance.
- Initial native client reached the disposable landmark world during driver
  prewarm. It was saved/exited to title before testing the corrected layout. Those
  vanilla-only frames are not hybrid lighting or performance evidence.
  At 20:38:24 the full-pipeline worker had run 882s (847s thread CPU) and was in
  `vkCreateComputePipelines`, not waiting on a Java monitor. This experiment does
  not fix cold-driver compilation latency. Full-lighting timing remains pending.
  Quit Game was requested at 20:39:09. At 20:40:29 the process was still waiting
  safely for one native compiler worker before device shutdown. No forced kill
  was issued; neither clean shutdown nor corrected-layout runtime is yet verified.
  Continuation evidence: `/tmp/lumen-hybrid-runtime.8hmQNA/client.log`, Gradle
  session 73180, original Minecraft PID 2715447. After confirmed exit, restart
  using `runtime.init.gradle` in that directory and the corrected build. The
  isolated world contains near blue (z=16) and far orange (z=96) landmarks.
- Independent review requires user approval for a read-only reviewer and is still
  pending. Apple Silicon/MoltenVK is not available in this local environment.

## FPS profiling and CPU allocation follow-up — 2026-10-03

This continuation supersedes the earlier pending shutdown/runtime observations,
not the outstanding visual acceptance or independent review gates above.

- The initial native full pipeline eventually compiled in 1,106,761 ms; the client
  subsequently quiesced Vulkan workers and exited successfully. Warm-cache runs
  avoided that cold compile. CPU edits below do not fix cold driver compilation.
- Corrected-layout native runs showed the far orange landmark at z=96 while ray
  distance remained 32, with near RT shading, hand and HUD present. This is narrow
  opaque-landmark evidence, not transparent-surface or full visual acceptance.
- Profiling device: Linux, Xeon E-2236 (6C/12T), RTX 5060 Ti 16 GB, NVIDIA 595.84,
  approximately 62.7 GiB system RAM. This is not a Mac/MoltenVK measurement.
- Fixed settings: hybrid terrain + shared-filtered-trace candidate, 960x540 window,
  480x270 internal rendering, render distance 12 chunks, simulation distance 4,
  ray distance 32 blocks, GI LOW, shadows LOW, reflections off, STABLE temporal,
  FAST denoise, VSync on and 30 FPS cap. Java 25.0.3, `-Xmx16G`.
- JFR initial-load samples identified cube UV scratch arrays and boxed vertex
  coordinates as allocation hotspots. Cube UV canonicalization now uses packed
  integer corner indices, reuses an immutable surface for identity ordering, and
  preserves tint. Model/outline coordinates now use Minecraft's existing fastutil
  primitive float container; no required player dependency was added. Geometry,
  ray distance, quality settings and Vulkan synchronization were not changed.
- A fixed-input helper microbenchmark (four vertex rotations, 25% identity) measured
  144 versus 42 allocated bytes per face, a 70.8% reduction in that helper only.
  Timings were noisy; this is not an overall allocation or FPS percentage claim.
  Bytecode inspection confirms `FloatArrayList.add(float)` without `Float.valueOf`.
- After the first optimization, F3 showed 12 FPS in the night landmark scene.
  GI-composite probe samples were about 90–92 ms (10.8–11.1 equivalent FPS).
  The probe measures submission-to-fence completion intervals, not GPU timestamp
  duration or Minecraft presentation FPS. Normal/direct-light diagnostic modes
  were around the 30 FPS cap, while indirect GI was about 73–80 ms. GI remains
  the principal steady-frame bottleneck. Daytime/mobs/loading were not frozen
  between runs; these observations do not establish a before/after FPS gain.
- Memory snapshot after the first optimization: maximum Java heap 16 GiB,
  committed heap 880 MiB, used heap about 490 MiB, process RSS about 2.0 GiB,
  GPU allocation reported by nvidia-smi 488 MiB. Heap limit is not actual RAM use.
- Final offline build after both CPU changes passed in 1m12s: 174 unit tests,
  zero failures/errors, owning shader and mixin checks passed. Canonicalizer tests
  cover all 24 corner permutations on all three axes, malformed inputs, offsets,
  boundary tolerance, tint and identity reuse. Dedicated-server entrypoint smoke
  after the first change reached the EULA gate and exited 0; no world was started.
  No shared API, Observer, networking or server-authority contract was changed.
- Evidence (local temporary files): `/tmp/lumen-hybrid-runtime.8hmQNA/` contains
  `fps-before.jfr`, `fps-steady.jfr`, `fps-after.jfr`, `fps-final.jfr`,
  `fps-build-final.log`, client logs/screenshots and `CanonicalizerBench.java`.
  Mac runtime, controlled FPS comparison and full hybrid visual acceptance remain
  outstanding; this build is not a release or a claim of resolved low FPS.
- Final client with both CPU changes entered the landmark world with full lighting
  active. F3 snapshot showed 13 FPS; late probe samples were 72.0–73.6 ms
  (13.6–13.9 equivalent FPS), not a controlled improvement ratio. The scene had
  changing daylight and entities. Heap snapshot: 16 GiB maximum, 832 MiB committed,
  about 440 MiB used; RSS 1.91 GiB and GPU 488 MiB. JFR no longer ranked appendQuad
  as the dominant Lumen allocation site. This confirms the changed path runs, not
  a statistically controlled allocation/FPS comparison.
  Save/Quit logged world saves, pipeline-cache closure and quiesced Vulkan workers;
  Gradle logged `BUILD SUCCESSFUL in 4m 5s`. The enclosing xvfb-run session reported
  status 143, so only the logged application cleanup is verified, not an exit-0
  result for that outer harness.

## Primary surface reuse candidate — 2026-10-03

Opt in separately with `-Dtotem.lumen.reusePrimarySurface=true` (process-fixed,
restart required). Default remains off pending native/MoltenVK visual and timing
acceptance. This option affects the full GI shader only; reflection source and
baseline shader hashes are unchanged. It composes with the hybrid terrain and
shared-filtered-trace options above.

The GI hit branch resolves one `P18SurfaceSample` and passes that value explicitly
through temporal GI, the GI sample loop, direct environment light, local light and
composite. Previously these consumers resolved the same primary surface N+3 times
at source level (N = GI samples). Bounce hits keep their own material evaluation;
sky/debug paths, sampling sequence/counts, ray limits, shading arithmetic,
transmission/alpha rules and history validation are unchanged. There is no shared
memory or cross-frame cache, additional GPU allocation, or CPU/GPU ABI change.
Potential shader register pressure/copy cost means fewer decodes are not proof of
faster execution; compare fixed-scene GPU/frame timings before adopting it.

`verifyPrimarySurfaceReuse` exercises the complete production source, checks
single primary decode and parameter forwarding, compares unchanged environment/
local-light bodies and traversal, tests transform composition, and rejects source
drift, duplicates and the wrong pass. Baseline and shared-filtered-trace hybrid
candidates both pass shaderc plus the existing material/fluid/entity checks.
Full offline build passed in 1m12s, 174 unit tests with zero failures/errors.
No shared contracts or server/common sources were changed. Native runtime was
started with the candidate in an isolated test directory; acceptance is pending.
Evidence: `/tmp/lumen-surface-reuse-check.log`,
`/tmp/lumen-surface-reuse-build.log`, `/tmp/lumen-surface-reuse-client.log`.

Dedicated-server entrypoint smoke exited 0 in 56s at the unaccepted-EULA gate;
no server world was started (`/tmp/lumen-surface-reuse-server.log`). Native full
pipeline prewarm started at 21:50:52 Taiwan time and had not completed at 21:53:38.
The disposable world was saved/exited to title while the driver worker continues.
Continuation: client PID 3205234, Gradle session 42075, display :103 with
Xauthority `/tmp/xvfb-run.GH004k/Xauthority`, init script
`/tmp/lumen-hybrid-runtime.8hmQNA/surface-reuse.init.gradle`. Do not force-kill the
native compiler or count fallback frames as full lighting. Re-enter only after
full pipeline READY and collect fixed-scene FPS/heap/RSS; MoltenVK is unavailable
locally. This candidate is implemented/build-validated, not runtime-accepted.

### Continuation / same-JAR A/B handoff

At 22:04:17 the full candidate pipeline finished native driver prewarm in
805,782 ms (13m26s). At 22:18:59 the candidate reached full-lighting READY in the
landmark world. Near shading and the far orange landmark remained visible.
This does not cover glass/water/animation/moving-camera or MoltenVK acceptance.

Initial automation failed to enter some world commands, so those early samples
are not fixed-scene evidence. The log confirms time set at 22:20:36, clear weather
at 22:20:38, and tick freeze only at 22:21:34. Subsequent GI-composite probe samples
were 81.817, 81.537, 78.611 and 78.701 ms (12.2–12.7 equivalent completions/second).
No matched baseline run was collected: do not compare this directly with the prior
night/day changing-scene FPS or infer a speedup. Hardware remains Xeon E-2236 + RTX
5060 Ti. Current options report simulation distance **12**, not the earlier noted
4; render distance remains 12, viewport 960x540/internal 480x270, ray32, GI/shadows
LOW, reflections off, STABLE temporal/FAST denoise, VSync on, 30 FPS cap.

Post-freeze memory snapshot: Java maximum 16 GiB, committed 1,032 MiB, used about
502 MiB; RSS about 4.26 GiB. This process retained native memory after cold driver
compilation, unlike earlier warm launches; these RSS snapshots are not evidence
of a controlled memory regression. Shader compilation/cold startup and warmed
in-world measurements must be reported separately.

Prepared local artifact: `build/handoff/totem-lumen-alpha61-surface-reuse-ab-ff53caf9.jar`.
SHA-256: `ff53caf943c84affa5f240b4b3b41f9ad580c37dc431b40c3e08852555910a93`.
It is the build-validated artifact, not a published release or a new version tag.
Independent handoff review is pending user permission for a read-only reviewer.

Mac A/B instructions (launcher JVM arguments, not game commands):

- Use the same JAR for both runs; remove the other Lumen JAR from this test
  instance's mods folder. Use a backup/disposable world and Java 25 (ARM64 on
  Apple Silicon), Minecraft 26.3, compatible Fabric Loader/API as declared by JAR.
- Keep `-Dtotem.lumen.hybridTerrain=true` and
  `-Dtotem.lumen.shaderVariant=shared-filtered-trace` identical in both runs.
- A: `-Dtotem.lumen.reusePrimarySurface=false`.
- B: `-Dtotem.lumen.reusePrimarySurface=true`.
- Fully restart between A/B. Wait for full-lighting READY, not just bootstrap or
  vanilla fallback. First shader/driver compilation time is not game FPS. Repeat
  warm A/B runs in alternating order with the same camera, world/time/weather,
  mob count, resource packs, viewport/internal resolution, render/simulation/ray
  distances, quality, VSync/FPS cap and JVM heap limit. Do not clear shader caches
  between warm runs. Do not add duplicate/conflicting JVM property arguments.
- Capture at least 60 seconds after load/compile settles. Report Mac chip/GPU,
  total RAM, Java architecture/version, `-Xmx`, actual heap/RSS, FPS/frame-time
  sampling method and settings. Include logs and visual differences; do not use
  one F3 snapshot as an average or infer 1% lows from these completion probes.
- This compares only shader surface reuse; both A/B include the earlier CPU
  allocation fixes. Neither mode changes configured terrain visibility distance.

Final screenshot `surface-final-runtime.png` showed a 17 FPS F3 instantaneous
reading with the integrated server marked frozen. It is not the mean of the
earlier probe window. At 22:22:28 the test saved/exited, closed the pipeline cache
and quiesced Vulkan workers; Gradle logged BUILD SUCCESSFUL (32m16s including
idle/title and driver compilation), and PID 3205234 was no longer present. The
old orchestration session handle was unavailable, so no outer exit code was
recovered. Local handoff copy SHA and packaged optimization classes were checked.
# Three-profile frame reporting — 2026-10-03 follow-up

## Measured baseline (not an optimization speedup claim)

Linux native Vulkan, Xeon E-2236 (6C/12T), RTX 5060 Ti 16 GB,
driver 595.84, 64,208 MiB system RAM (62.7 GiB), Java 25 with actual
`MaxHeapSize=17179869184` (16 GiB). Minecraft 26.3 / Loader 0.19.5 /
Fabric API 0.160.5+26.3. Same running JVM and isolated test world/camera;
960x540 viewport, render/simulation distances 12/12, 30 FPS limit,
VSync on. World time 6000, clear weather and frozen ticks confirmed in
chat at 22:52:31–22:52:41. This stationary/frozen scene is not a gameplay
or chunk-flight benchmark.

| Profile | Report time (Asia/Taipei) | Samples / seconds | Average FPS | 1% low FPS | p95 interval ms | Heap used MiB | Process RSS GiB |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Minecraft Pure | 22:56:46 | 1800 / 60.01 | 29.99 | 27.93 | 34.72 | 911 | 2.36 |
| Minecraft RGB | 22:59:06 | 1800 / 60.01 | 30.00 | 25.25 | 34.61 | 1138 | 2.38 |
| Totem Lumen | 22:54:01 | 1045 / 60.03 | 17.41 | 10.79 | 86.91 | 1014 | 2.33 |

One complete window per profile, not three repeated windows. All heap
committed snapshots were 1312 MiB; heap max was 16384 MiB. RSS was read
with `ps` shortly after each window: Totem 2446660 KiB, Pure 2478248 KiB,
RGB 2493336 KiB. These are snapshots, not peak/mean memory measurements.
Totem ran first, so Pure/RGB RSS includes resources retained by this JVM.
Pure/RGB hit the configured FPS cap: this does not measure their maximum
throughput or establish their performance equivalence.

Totem used GI LOW, shadow LOW, ray distance 32, internal LOW (480x270),
reflections off, temporal STABLE, denoise FAST. Experiment flags:
`hybridTerrain=true`, `reusePrimarySurface=true`,
`shaderVariant=shared-filtered-trace`. Both experiment toggles still
default off. Full pipeline warm-cache prewarm took 728 ms; in-world
GI Composite readiness was confirmed before sampling. This is not a
matched reuse-off/on result, and the old F3 snapshot is not a baseline.

Validation: full Gradle build passed in 1m31s; 179 unit tests, zero failures,
including 5 new frame-window tests (warmup, weighted FPS/slow tail, capacity,
reset, clock/argument validation). Shader and Minecraft descriptor gates
passed. Three native Vulkan profile windows were produced by the existing
renderFrame mixin; menus/profile switches reset partial windows.
Evidence: `/tmp/lumen-frame-metrics-build.log`,
`/tmp/lumen-frame-metrics-client.log`. Apple Silicon/MoltenVK runtime and
independent review remain pending; no publication or performance gain claimed.

The original Totem profile was restored, the isolated world was saved and
the client exited successfully. Logs confirm pipeline-cache session closure
and device-worker quiescence before backend destruction.
Dedicated-server entrypoint smoke in a fresh temporary directory completed
in 22 seconds and stopped at the unaccepted EULA gate; no world or listening
server was started. This is a class-isolation/startup check, not server
gameplay acceptance. Log: `/tmp/lumen-frame-metrics-server.log`.
Local test artifact (not uploaded/published):
`build/handoff/totem-lumen-alpha61-frame-metrics-4a1054fc.jar`;
SHA-256 `4a1054fcf1c136a4b4a57d83a39afb42665afb0427ea412df42eb3a2dcf02c65`.

## Unlimited FPS / VSync off rerun — 2026-10-03

User requested this settings-only rerun. No implementation or shader changes;
same alpha61 build SHA-256
`4a1054fcf1c136a4b4a57d83a39afb42665afb0427ea412df42eb3a2dcf02c65`.
The native Video Settings screen explicitly showed **Max Framerate: Unlimited**
and **VSync: OFF**. Minecraft serializes this Unlimited option as
`maxFps:260`; it must not be described as a 260 FPS cap.

Same Linux/Xeon E-2236/RTX 5060 Ti machine, Java 25, 62.7 GiB system RAM,
16 GiB Java heap limit, 960x540 viewport, render/simulation distances 12/12,
and the same Totem quality/experiment flags listed above. Pipeline warm-cache
prewarm completed in 295 ms. All three windows were in the same restarted JVM,
world and camera view; time 6000, clear weather and frozen tick confirmations
were logged at 23:13:46, 23:13:51 and 23:13:57 respectively.
Entities moved between restarting the world and freezing it: the scene is
consistent within this rerun, but not pixel-identical to the previous run.

| Profile | Previous average (cap 30 / VSync on) | Unlimited / VSync off average | New 1% low | New p95 interval ms | Heap used MiB | Process RSS GiB |
| --- | --- | --- | --- | --- | --- | --- |
| Minecraft Pure | 29.99 | 173.96 | 28.96 | 32.53 | 621 | 1.94 |
| Minecraft RGB | 30.00 | 174.36 | 27.88 | 32.18 | 427 | 1.95 |
| Totem Lumen | 17.41 | 22.00 | 9.46 | 85.28 | 497 | 1.92 |

Exact report windows (Asia/Taipei): Totem 23:15:17, 1320 samples / 60.00 s;
Pure 23:17:55, 10440 samples / 60.02 s; RGB 23:20:12,
10466 samples / 60.03 s. Each had 20 s of discarded warmup. No overflow
window was reported. Actual runtime logs confirm `fpsLimit=260, vsync=false`
for every window; heap committed was 872 MiB and max 16384 MiB throughout.
RSS snapshots shortly after the respective windows were Totem 2009388 KiB,
Pure 2039388 KiB and RGB 2041172 KiB, not peaks or independent cold-start
footprints. Different GC/heap state prevents calling lower RSS a memory
optimization.

Pure/RGB averages are close in this single stationary sample; repeated runs
and moving/chunk-loading workloads are needed before claiming equivalence.
Totem average increased while its 1% low fell from 10.79 to 9.46 FPS:
uncapping has not established a fix for stutter. Changes in settings and
entity poses prohibit attributing the whole difference to one cause.
These remain game-loop intervals, not unique RT-frame rates or Mac results.

Evidence: `/tmp/lumen-uncapped-client.log`;
settings-screen verification `/tmp/lumen-uncapped-video-after.png`.
The test world was saved, the original Totem profile restored, and Unlimited /
VSync OFF left in the isolated test options as requested. Client exited with
status 0; pipeline-cache closure and device-worker quiescence were logged.
This rerun reuses the prior 179-test/build/shader/descriptor/server-smoke
evidence for unchanged code, with fresh native Vulkan runtime measurements.
No publish/commit/push. Existing Apple Silicon and independent-review gates
remain open.

## Sampling contract

Opt in with `-Dtotem.lumen.frameMetrics=true` (the old
`totem.lumen.rgb.frameMetrics` property remains an alias). The existing
`Minecraft.renderFrame` hook now supports Pure, RGB and Totem Lumen.
It discards 20 seconds of eligible warmup, then collects at least 60 seconds
of consecutive frame-start intervals. A full last interval is retained;
slow frames are never trimmed. Windows use a fixed 120,000-entry buffer
(960,000 bytes); overflow discards the entire window with a warning.
Disabled launches do not allocate this buffer.

Average FPS is interval count divided by total interval duration. 1% low is
the reciprocal of the mean of the slowest ceil(N/100) intervals, not the
reciprocal of p99. These are game-loop wall-clock rates, not GPU timestamps,
unique ray-traced output rates or display-present latency. Totem may present
the same completed ray-traced result on multiple game frames.

World changes, menus/overlays, loss of focus, pause, profile/settings revision,
resolution, render/simulation distance, frame limit and VSync changes discard
partial windows. Totem requires a completed world-takeover frame and GI
Composite mode; loading/fallback/debug frames do not count. Each report includes
the actual settings and a heap-used/committed/max snapshot (not RSS). Sorting
and logging happen between windows, followed by fresh warmup.

For comparisons, use the same world, camera, time/weather, entity conditions,
resolution, frame cap/VSync and shader experiment flags. Record hardware,
process RSS and shader-cache state separately. A capped run does not measure
maximum throughput. Historical 600-frame RGB logs use the previous sampler
and must not be combined with these 60-second windows.

## Raster-primary prototype (2026-10-04, not a release)

`RASTER_RAY` is a fourth, opt-in profile, separate from the older `hybridTerrain`
experiment. Minecraft retains visibility, textures, entities, sky, transparent
surfaces and distant geometry. A small Vulkan fragment pipeline reconstructs
primary positions and approximate normals from depth; only secondary rays use
the voxel atlas. It never initializes P5/P12 or traces primary visibility rays.

The first iteration is deliberately **not feature-equivalent** to full Totem:
it adds bounded ambient occlusion and sampled emissive one-bounce correction
over already-lit native color. It does not provide physical albedo, directional
sun shadows, transmission, refraction, metallic reflections, dynamic-entity
occlusion, exact cutout/model geometry, temporal accumulation or denoising.
Transparent objects remain visible through vanilla rendering, but their ray
lighting is not correct yet. Unknown voxel cells are neutral, not opaque.

- Lighting radius and secondary-ray length: fixed 24 blocks; terrain distance is unchanged.
- GI quality: 1/2/3 samples, at most 64 DDA iterations per sample.
- Internal resolution: existing bounded LOW/BALANCED/HIGH pixel budgets.
- CPU volume: 96 cubed, 216 immutable 16-cubed sections; at most two sections
  captured per tick, with a 2 ms checkpoint between sections (one section can exceed it).
- Periodic refresh rather than event-driven updates: a full sweep takes at least
  108 ticks. Changes can lag a sweep; moving across a section boundary discards
  the previous volume. This is a known prototype limitation, not final latency.
- Atlas: 256 x 3584 RGBA8, 3,670,016 bytes. Only changed section tiles upload.
- Temporary GPU targets: full-size native color copy and bounded RGBA16F
  lighting-factor/distance; depth-aware composition multiplies full-resolution
  native surface color, retaining texture detail and native silhouette/far pixels.
- Inactive profiles do not submit this pass or capture its scene. Full Totem
  extraction/model/PBR and RGB prediction are gated separately. Profile changes
  discard old CPU generations; resource retirement is permitted while inactive.
  Native driver compilation already in progress cannot safely be forcibly killed.
- FPS eligibility requires a fully visited volume and successful pass submission;
  startup/fallback frames are excluded. This is not proof of full-lighting fidelity.

Extension path: replace depth-derived normals with captured normals/material
IDs and unlit albedo, then add a distinct transparent layer and bounded
transmission/refraction traversal. Do not infer transparent materials from a
single opaque depth buffer. Keep each path owned by the selected profile.

Initial deterministic checks: Java 25 / Minecraft 26.3 build passed; 187 tests,
zero failures/errors, including atlas mapping, defensive copies, negative
coordinates, profile gates and ray budgets. Both new shaders passed SPIR-V
compilation. Native runtime results, Apple Silicon/MoltenVK and independent
review remain separate gates; no FPS gain is claimed by this build result.

### Runtime corrections before acceptance

The first native launch correctly failed closed when clearing the atlas required
`USAGE_RENDER_ATTACHMENT`; the atlas now declares that usage. The failed launch
submitted zero experimental frames and is not a performance result.

The next visual comparison caught native texture detail being downsampled with
the lighting. The low-resolution pass now produces only a lighting multiplier;
the full-resolution pass samples native color at the original pixel. Its earlier
208.64 FPS sample is retained in `/tmp/lumen-raster-ray-client-v2.log` as rejected
intermediate evidence, not the current candidate's performance.

Minecraft 26.3 `FramerateLimitTracker.getThrottleReason()` also reveals an AFK
30-FPS limit after 60 seconds without input, even with the Unlimited slider.
That contaminates a 20-second warmup + 60-second stationary window. Historical
AFK-enabled "uncapped" samples must not be treated as unrestricted throughput.
The corrected test fixture uses `inactivityFpsLimit:"minimized"`, a focused,
non-minimized window, `maxFps:260` (the UI's Unlimited setting) and VSync false.
Do not compare corrected results to the earlier AFK-contaminated numbers as a
code-only speedup. The sampler itself does not yet reject AFK throttling; this
setting is a mandatory benchmark precondition.

### Corrected four-profile native Vulkan run

Candidate SHA-256: `11bc005d2eabbbace9b91b96dde86b0691032ccf97bfced7732d87fb89532aee`.
Minecraft 26.3, Java 25.0.3, Linux, Xeon E-2236, RTX 5060 Ti (16 GB,
driver 595.84), system RAM 62.7 GiB; JVM maximum heap 16 GiB, committed 840 MiB.
Viewport 960 x 540, render/simulation distance 12/12, Unlimited, VSync off,
inactivity limit Minimized. Same loaded test world/camera, time 6000, clear
weather and frozen ticks. Each window follows 20 seconds of warmup and records
60 seconds. These are game frame-start intervals, not GPU completion timings.

| Profile | Window means (FPS) | Window 1% lows (FPS) | Heap-used snapshots (MiB) | RSS snapshot (KiB) |
| --- | --- | --- | --- | --- |
| Minecraft Pure | 334.40 / 290.10 | 136.06 / 125.71 | 565 / 445 | 1,997,836 |
| Minecraft RGB | 316.59 / 290.42 | 102.68 / 104.09 | 570 / 658 | 2,030,396 |
| Totem Lumen | 25.51 / 26.62 / 26.25 | 10.82 / 12.59 / 12.39 | 761 / 729 / 671 | 2,120,608 |
| Raster Ray experimental | 344.47 / 282.48 | 145.88 / 100.38 | 381 / 545 | 1,985,548 |

Log: `/tmp/lumen-raster-ray-client-v3.log`. Raster windows: 00:39:55 and
00:41:15; Pure: 00:42:50 and 00:44:10; RGB: 00:46:28 and 00:47:48;
Totem: 00:50:29, 00:51:49 and 00:53:09 (2026-10-04 Asia/Taipei).
All available initial comparison windows are reported, not only the fastest.
Later switching/resize/rejoin checks are lifecycle probes, not additional A/B windows.

Totem uses `shared-filtered-trace`, `hybridTerrain=true`,
`reusePrimarySurface=true`, GI/shadow LOW, ray distance 32, LOW 480 x 270,
reflections off, temporal STABLE, denoise FAST. Raster Ray uses LOW 480 x 270,
one secondary sample, ray distance 24, ambient occlusion only (no directional
shadow pass), reflections/temporal/denoise off. The same experiment properties
are present in the launch but inactive full-renderer profiles do not execute
those pipelines. Pure/RGB are native-renderer profiles with the mod installed,
not a clean unmodded baseline.

Raster Ray and native profile ranges overlap; these samples do not establish a
significant speedup versus native rendering. Raster Ray is much less complete
than Totem, so their difference is **not an equal-quality optimization claim**.
Heap snapshots include GC variability and are not a memory-leak measurement.
No result here predicts Mac mini performance.

Native evidence: new profile started without P5/P12 bootstrap; its scene and
targets retired on switch to Pure; switching through RGB and Totem back to
Raster Ray succeeded. Full Totem's scene backlog was zero in debug diagnostics.
Near blue terrain and the orange wall 96 blocks away remained visible. Resizing
960 x 540 -> 800 x 450 -> 960 x 540 rebuilt lighting targets 480 x 270 ->
400 x 225 -> 480 x 270 without a renderer error. The headless audio startup
error is unrelated to rendering. Dedicated-server entry initialized gameplay
lighting and stopped at the unaccepted EULA gate; this is not a full server
world/GameTest run. Apple/MoltenVK, transparent/material fidelity and independent
review remain unvalidated; no commit, push or publication was performed.

### Final disconnect-thread correction (v4)

The v3 reconnect probe exposed a Netty-thread DISCONNECT callback retiring the
new raster resources. Removed raster scene/GPU cleanup from that callback;
client-level-change, client tick and CLIENT_STOPPING retain ownership. Added a
regression guard against direct raster destruction from the network callback.

Final candidate SHA-256:
`05d31f01ebfd7caf66f6756964fcfc7c229513cbc74bca8402e64abe8cbc017a`.
Java 25 build passed in 1m 13s: 188 tests, zero failures/errors/skips; owning
shader/mixin checks passed. The FPS table above was measured on v3, not this
v4 artifact; this last change affects disconnect cleanup, not the measured
in-world rendering path. No new FPS claim is inferred from that distinction.

Native v4 log `/tmp/lumen-raster-ray-client-v4.log` confirms startup without
the full Totem bootstrap, RASTER_RAY ACTIVE at 01:08:00, scene/resources retired
on **Render thread** at 01:08:50, and rejoin ACTIVE at 01:09:11 (2026-10-04,
Asia/Taipei). Near/far geometry remained visible in the in-world probe. These
are development-client production-render-path checks, not a packaged-JAR
installation test. Independent review and Apple Silicon/MoltenVK validation
are still pending; the experimental profile is not a release-ready renderer.
Final exit retired raster resources on Render thread at 01:09:42 and quiesced
Vulkan device workers before backend destruction at 01:09:49; the run exited
successfully. No raster render failure was logged (headless audio remained
unavailable, as in the earlier fixture).
