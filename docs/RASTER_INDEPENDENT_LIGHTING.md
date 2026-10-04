# Raster independent lighting — staged work, 2026-10-04

## Requested target (NOT implemented yet)

Keep native raster geometry and view distance. Replace vanilla **visual** lighting
with Totem lighting across the visible world, with fine near-field and coarser
far-field data. Keep server gameplay/spawn lighting intact and run visual work
only for the selected profile. Include RGB using the established Minecraft RGB
profile's source definitions.

### Agreed direction: RGB ray-traced profile

Develop the existing `RASTER_RAY` path into RGB ray-traced lighting, retaining
native geometry/view distance and sharing RGB source definitions, NOT executing
Minecraft RGB and Totem Lumen renderers simultaneously. Keep Pure/RGB/full Totem
profiles separate; this direction does not introduce a fifth profile or rename
persisted settings yet.

Order: stabilize near-field direct RGB/occlusion/material response; add sky/day-night
and coarse far-field coverage; complete entity/transparent coverage before claiming
full visual-lighting replacement; then extend transmission and advanced material
effects. Native fallback remains for unsupported/unready coverage. Server gameplay
lighting is not removed. Current preview is not full-world lighting or path tracing.

## Stage 1 implemented: bounded reusable scene input

- Rolling, same-world section reuse at camera section transitions. An axial
  one-section move retains 180 of 216 immutable sections. Teleports outside the
  window, world changes and profile transitions do not reuse old-world data.
- Centre-first acquisition and coalesced block/chunk invalidations; three urgent
  captures alternate with one round-robin refresh so repeated edits cannot starve
  resident refreshes. Immutable snapshots do not change after publication.
- At most two section captures per tick, checking a 2 ms deadline **between**
  captures. A single capture may exceed that deadline; this is not a hard 2 ms cap.
- At most eight section uploads (128 KiB) per frame, excluding the fixed atlas
  clear on rebase. Pending uploads submit without compositing: the native frame
  remains visible, and stale slot contents never shade a rebased world.
- RGB source packing calls the same stateless `ClientRgbVisualLightSource` as
  Minecraft RGB, including server rules/strength and the visual +1 adjustment.
  It does not run `ClientGameplayLightPredictor`. Brightness multiplier applies
  during composition; server-rule changes request a fresh bounded window.
- `RasterLightingCoverage` specifies **future** 1/2/4/8/16/32-block clipmap
  cells, enough to cover 1–32 chunk view corners with at most six fixed-size
  atlases (21 MiB raw RGBA atlas storage). This class is a tested planning ABI,
  NOT connected far-field extraction or GPU rendering.

## Rendering paths and staged limits

### Stage 2e implementation: owned surface handoff replaces material replay

The staged-render branch supersedes the Stage 2a-2d **material replay mechanism** while preserving
those sections below as historical experiment evidence. The crash-producing path is removed from the
active mixin list and its replay classes/shaders are deleted.

After Minecraft completes world drawing, `RasterSurfaceCapture` now creates a frame-local owned
surface:

- native scene color -> Totem-owned color texture via GPU copy;
- native depth -> Totem-owned `R32_FLOAT` texture via a small fullscreen depth-capture pipeline;
- an owned full-resolution approximate normal field -> `RGBA16_FLOAT`, reconstructed once from
  native depth inside SURFACE_CAPTURE;
- `RasterSurfaceFrame` carries device, extent, frame serial, color semantic, color/depth/normal
  views to later stages;
- Raster lighting no longer reads the main target depth directly and no longer owns its own duplicate
  scene-copy resource;
- client tick/stop lifecycle explicitly retires the surface stage.

The published color semantic is `NATIVE_LIT_COLOR`. Therefore the earlier diagnostic independent
RGB/material mode is intentionally not active on this safe path: multiplying Totem independent light
over already-lit vanilla color would be a false material model. A future MATERIAL_RESOLVE stage must
produce `UNLIT_MATERIAL_COLOR` plus owned normal/material identity before that lighting mode returns.

This change is an architectural/lifetime fix, not visual or FPS acceptance. Required native gates:
resize, profile switch, disconnect/rejoin, world exit/re-entry and shutdown with no closed-buffer or
render-pass errors, followed by fixed-scene output checks. Apple/MoltenVK remains a separate gate.

### Stage 2f implementation: active secondary lighting split into INDIRECT_GI + COMPOSITE

The safe `NATIVE_LIT_COLOR` path no longer compiles the old combined `raster_ray` fragment shader.
That shader still contained direct-RGB point-light selection/visibility and material-preview branches
that could not legally run without unlit material input.

The active graph is now:

```text
SURFACE_CAPTURE
      |
      v
INDIRECT_GI
      |
      v
COMPOSITE
```

`RasterIndirectGiStage` owns the voxel atlas and reduced-resolution radiance texture. Its shader
consumes `DepthSampler`, `NormalSampler` and `VoxelSampler`; primary normal reconstruction has
moved entirely into SURFACE_CAPTURE. It performs the existing bounded secondary diffuse/emissive and
occlusion correction and traces no primary visibility rays. `RasterLightingFrame` carries the
radiance view plus the source surface frame serial. `RasterCompositeStage` validates that serial
before sampling the surface and lighting outputs and is the sole staged pass writing the world color
target.

The direct-light data structures remain tested research code, but section extraction no longer builds
their emitter summaries and the active shader has no `LightSampler`, `directRgb` or point-light
visibility loop. DIRECT_LIGHT returns only after MATERIAL_RESOLVE publishes `UNLIT_MATERIAL_COLOR`.
This is a compilation/ownership separation, not a claim that the current native-lit indirect
correction equals the future independent-lighting renderer.


### Stage 2g implementation: partial unlit material coverage

The optional MATERIAL_RESOLVE development stage now publishes two additional owned full-resolution
outputs without replaying Minecraft draw buffers.

- `baseProperties` is RGBA16F and decodes the existing MaterialDefinition ABI as base
  roughness / metallic / opacity / normalized emission level.
- `unlitAlbedo` is RGBA16F and is currently valid only for static canonical textured cubes.
  The stage reuses the existing P18 cube surface-set ABI (face UVs, texture handle and captured
  biome/block tint) plus the packed P18 texture-scene ABI. RGB is unlit sampled albedo multiplied by
  tint; alpha is **coverage**, not permission to replace every raster surface.
- Surface-set ID zero, missing/unloaded texture handles, animated textures, unsupported geometry and
  unresolved pixels return zero coverage and therefore require native-lit fallback.
- The large P18 texture scene is uploaded only when the P18 texture registry revision changes.
  Material-LUT resizing, surface-set LUT lifetime and P18 texture LUT lifetime are independent.

Generic meshes, fluids, entities and animated textures still lack complete unlit surface coverage,
and P18 sampled normal/specular/AO data has not yet been promoted to resolved per-pixel outputs.

An isolated DIRECT_LIGHT diagnostic producer now exists behind
`-Dtotem.lumen.rasterDirectLightStage=true` together with MATERIAL_RESOLVE. It reuses the shared
VOXEL_SCENE GPU atlas, selects at most 32 RGB emitters and traces at most four bounded visibility
rays for material-covered pixels. Its output is not read by COMPOSITE yet. Therefore the active
accepted renderer remains `SURFACE_CAPTURE -> INDIRECT_GI -> COMPOSITE`, and unsupported pixels
continue to use native-lit fallback.

### Stage 2d candidate: stable publication and light payload order

Unchanged section refreshes previously published a fresh volume every tick, so the
renderer's volume-identity cache reselected lights even with a stationary camera
and unchanged scene. Cache the immutable published snapshot in the rolling window;
invalidate it only when a section payload identity changes. Dirty scheduling still
runs, invalidation still publishes unknown data immediately, and rebase/world reset
creates a separate window/snapshot. Never mutate a previously published volume.

Distance ranking chooses the nearest 32 emitters as before, but serialize the
selected set in canonical y/z/x order. Camera movement that only reorders distance
ranks must not change the GPU payload. This also makes equal-score shader top-four
ties independent of camera-distance ordering while membership stays unchanged.
Colour edits/removal still change the payload. Candidate/ray/texture budgets and
shader code are unchanged; cap-induced membership changes and per-pixel ranking
changes can still pop. This is not a fade, hysteresis or complete stability solution.

Five added regression tests failed on the old implementation (17 targeted tests,
five failures), then passed with the fix. Full Java 25 offline build passed in
1m 55s with 210 tests, zero failures/errors, and the owning shader/mixin checks.
Logs: `/tmp/lumen-stability-red.log`, `/tmp/lumen-stability-build.log`.
The tests cover unchanged refresh identity without starving refresh work,
edit/invalidate/restore immutability, rebase/world reset, unchanged selected-set
serialization across camera movement, colour edits, and a separate exhaustive
nearest-32 oracle including distance ties.
Impact found only Lumen and no shared consumers. No shader/GPU resource layout,
UI/Observer, network, server authority or gameplay rule changes.

Native validation FAILED its lifecycle gate. On Linux / RTX 5060 Ti (16311 MiB),
Xeon E-2236, system RAM 64208 MiB, Java 25 max heap 16 GiB, the preview presented
two temporary warm/cyan emitters at 960x540 and again after resizing to 1024x576.
Native-size screenshots `/tmp/lumen-stability-rgb.png` and
`/tmp/lumen-stability-resized.png` show the expected colours, but also the existing
overexposed white-floor overlap; no tone-mapping acceptance is claimed. The first
retirement logged 66 selections / 61.529226 ms / 768 light-upload bytes over 14723
raster frames including initial capture and fixture edits. This is not a matched
before/after timing or FPS result. No valid profile-FPS window was recorded.

`/tmp/lumen-stability-runtime.log` reports a swapchain-out-of-date warning at
15:02:13, followed by material-preview reactivation at 15:02:14. At 15:03:07,
`RasterMaterialCapture.render` replayed a native draw whose slot-0 vertex buffer
was closed. Closing that pass also failed with unbalanced debug groups, then
native HUD rendering failed because the prior render pass was still open.
`runClient` exited 255 (outer Gradle exit 1). Do not treat the catch-and-disable
message as successful native fallback: the frontend encoder remained invalid.
The capture code was not changed in this stage, but a pre-change reproduction
has NOT established whether this is pre-existing or introduced indirectly.
Crash report: `/tmp/lumen-hybrid-runtime.8hmQNA/run/crash-reports/crash-2026-10-04_15.03.10-client.txt`.
Borrowed draw-buffer lifetime and failure cleanup are the next blocking work;
do not enable this preview by default or publish it as stable. MoltenVK,
dedicated-server runtime and independent review also remain pending.
Fixture restoration completed separately with Pure mode: both temporary sources
were removed, the 208-block floor restored to grass, and the world saved at
15:08:52. Normal Quit Game quiesced Vulkan workers at 15:09:08; the cleanup run
returned exit 0 / `BUILD SUCCESSFUL` (`/tmp/lumen-stability-cleanup.log`). The
isolated profile configuration was restored to `RASTER_RAY` afterward. That Pure
cleanup run is NOT a retry/pass of the failed preview gate. Candidate JAR SHA-256:
`8e37393b965a5c8b87b333510f250b171db6862cf5aaa52866f0bed5c49cd459`.
No commit, push, publication or default-profile changes.

### Stage 2c candidate: bounded direct RGB emitters

The same opt-in material-lighting preview now adds direct point-emitter sampling.
Normal Raster/Pure/RGB/Totem paths do not build these emitter summaries or allocate
the direct-light texture. During the existing bounded section capture, preview
sections keep one real emitting cell in each 8x8x8 octant (at most eight per section).
Highest emission wins, then distance to the octant centre, then voxel index. This
preserves a lone small source but can omit weaker lights in dense arrangements.

From at most 216*8 representatives, a 32-entry bounded priority queue chooses the
nearest candidates to the camera cell. Coordinates remain local to the immutable
volume, including at negative world origins. Selection occurs when the published
volume or camera cell changes, not every unchanged rendered frame. A 64x1 RGBA8
texture stores position/RGB pairs, at most 256 bytes per changed list. Unchanged
lists do not upload; a changed device/window recreates and populates the texture.
Retirement logs selection count/CPU time and upload bytes. These are counters, not
a measured speedup claim.

Each low-resolution material-lighting pixel examines at most 32 candidates and
traces to at most four with highest estimated Lambert/falloff contribution. Each
visibility ray is bounded to 64 DDA iterations; unknown cells and solid blockers
reject the ray, while the selected source cell terminates it. The visual range is
12.5 blocks at full strength (15 / 1.20), with smooth radial falloff and a surface
cosine. The existing 1–3 hemisphere rays still provide occlusion and a reduced
emissive term. Therefore the upper bound is seven secondary rays, not four total.
All ray work remains within the near-field surface range; far geometry is retained.

This is an approximate point-light preview, NOT an area-light/direct-sun solver:
representative/32-light/4-ray caps can drop relevant sources, a blocked high-ranked
candidate can exclude a lower-ranked visible light, dense source interiors are not
surface-sampled, and hard voxel shadows remain. Opaque/cutout material capture still
does not represent transparency, fluid optics, entities or sky. No gameplay-light
data or shared module contract changes. Full production takeover remains disabled.

Initial Java 25 offline full build passed (205 tests, zero failures/errors), including
bounded selection/immutability/packing/removal tests, shader compilation and mixin
ABI checks. The original no-summary `Section(int[])` constructor remains unchanged
in behavior for existing window/volume consumers. No shared module consumers exist.
Frame-metric diagnostics now identify `rasterMaterialLighting` and direct RGB point
shadows instead of mislabelling this preview as ordinary AO-only Raster.

Native probe used the existing isolated world, LOW / 50% resolution, 960x540 and
1024x576, unlimited FPS, VSync off, render/simulation distance 12. Two single blocks
(sea lantern and shroomlight, suspended above a temporary white floor) produced broad
continuous cyan/warm illumination. A one-block-thick opaque wall separated their
illumination, and removing the cyan source removed its surface and blue contribution
on the wall/floor. Native-scale screenshots were reviewed using the art-direction
workflow. This confirms these fixtures, not arbitrary thin/cutout/translucent geometry.
Intensity/tone mapping and hard shadow/popping behavior still need work.

At the first resize, diagnostics recorded 2690 selection calls totalling 246.114486 ms
(about 0.0915 ms per selection), and 1280 bytes of light-list uploads. This is observed
CPU selection time, not an upper bound, GPU timing or FPS speedup. The viewport
rebuild logged two selected lights and populated the fresh 256-byte texture. Hardware
remains Linux / RTX 5060 Ti, 64,208 MiB system RAM, Java max heap 16 GiB. No controlled
per-profile FPS comparison or peak-RAM benchmark was run; any automatic windows
during edits/resizes are not comparable benchmark samples.

Probe log: `/tmp/lumen-direct-runtime.log`. Artifacts:
`build/reports/raster-material/direct-rgb-two-sources.png` and
`direct-rgb-occlusion.png`. Raster -> Pure at 14:21:40 retired direct-light resources
and restored native rendering; Raster resumed at 14:21:45 and reached ACTIVE with
one remaining source at 14:21:52. Temporary source blocks, the 30-block wall and
208-block floor were removed/restored before saving. World exit retired direct-light,
material and voxel resources at 14:22:39; Vulkan workers quiesced at 14:22:46.
The log ends with Gradle `BUILD SUCCESSFUL`; the outer Xvfb launcher session returned
143 rather than 0. Thus GPU/world cleanup is evidenced, but a clean outer-launcher
exit is not claimed. No Raster/Vulkan runtime exception was logged; pre-existing
OpenAL/Realms/end-of-frame warnings remain. The subsequent final build also includes
the metrics-label-only change (rendering code matches the native probe).
Final Java 25 offline full build passed in 1m 18s (28 tasks, 23 executed), including
the updated metric-label contract test. Log: `/tmp/lumen-direct-final-build.log`.
Candidate `build/libs/totem-lumen-0.1.0-alpha.61.jar` SHA-256:
`9f61c39eb144c77ed1244e5760ec4433fa3fefd227b03ac6b83e5a9520180e1f`.
This candidate hash supersedes the pre-metric-label build; it is not publication
or independent-review evidence.
Apple/MoltenVK, dedicated-server runtime and independent review remain pending.
No production default, release, commit, push or publication changes.

### Stage 2b candidate: material-fed RGB diagnostic lighting

`-Dtotem.lumen.rasterMaterialCapture=true` together with
`-Dtotem.lumen.rasterMaterialLighting=true` selects an **opaque-only diagnostic**
while `RASTER_RAY` is active. Both default off. RGB preview wins if raw preview is
also requested. Neither preview flag enables capture by itself. Raw preview skips
the normal correction pass; RGB preview replaces it, never stacks both passes.

Capture now precedes the lighting pass. Only the same frame's submitted material
colour and its own depth, from the same device and dimensions, may be consumed.
Frame start, retirement, skipped capture and failure invalidate that eligibility.
Missing inputs retain the untouched native frame. The lighting pass borrows views;
capture alone owns their deferred retirement. No native mesh/lightmap is modified.

The diagnostic uses a fixed 0.08 ambient floor, approximate depth normals, cube
occlusion and 1–3 emissive hemisphere samples (64 DDA steps / 24 blocks maximum).
RGB emission uses the existing stage-1 source rules. Ray-hit emission attenuates
linearly with distance along the ray; this is NOT the RGB profile's voxel-spread
attenuation or a direct-light solver. A behind-surface voxel estimate contributes
self-emission. Near-range weight fades both contributions; farther captured terrain
is still drawn with the ambient floor, not discarded. Missing opaque geometry is
black. Silhouette depth mismatch uses ambient rather than lighting a wrong surface.
Upsampling tolerance includes the continuous depth slope over half a lighting-texel
footprint; it takes the smaller of the two neighbouring depth changes on each axis,
so a single silhouette jump does not widen the threshold. The first native probe
exposed alternating dark floor rows with the old constant radial tolerance. This
diagnostic-only change does not alter the normal profile's composite policy.
The resize probe also exposed unstable depth-neighbour selection when a reduced
pixel falls on a full-resolution texel boundary. The shared Raster secondary pass
now reconstructs positions/normals at the selected depth texel's centre, avoiding
duplicate/double neighbour depth steps. A CPU float-address regression spans seven
source sizes and both integer and non-integer sampling ratios. This correction
also applies to ordinary Raster; ray count, budgets and transport remain unchanged.

Unlike the default Raster correction, this path multiplies **captured material**,
not native already-lit colour. Thus the native lightmap cannot suppress its RGB.
This remains diagnostic: native baked vertex shade is retained, emission is a
whole-voxel approximation and is modulated by base colour, tiny lights may be missed
by sparse rays, and there is no sun/sky model, true diffuse multi-bounce, PBR, fog,
entities, translucency or fluid coverage. It is not acceptable for production
takeover. Ordinary profile settings and server gameplay brightness are unchanged.

Java 25 offline full `build` passed in 1m06s: 200 tests, zero failures/errors/skips,
shader compilation, material pipeline/bridge ABI and configured-game mixin checks.
Log: `/tmp/lumen-material-rgb-final-build.log`. Impact remains Lumen-only with no
shared API/consumer changes; all implementation edits are client rendering or its
tests/documentation. Existing common gameplay tests run, but dedicated-server
runtime and Apple/MoltenVK are not revalidated in this stage. No new FPS benchmark
was run, and diagnostic opaque-only frames must not be compared with normal-profile
FPS. Candidate JAR SHA-256:
`7c38e037b92b68064204e67dba70fed32fe213964217c90689f579e728c23afe`.

Native Linux Vulkan / RTX 5060 Ti (16,311 MiB), system RAM 64,208 MiB, Java max
heap 16 GiB: the final probe reached RGB preview ACTIVE after loading the isolated
test world. LOW lighting (1 ray, 50% target), unlimited FPS setting and VSync off;
no FPS or peak-heap measurement was collected. Raw 960x540 and 1024x576 screenshots
were inspected at 1x under the art-direction workflow. Both showed sharp source
textures and warm/cyan patches on a temporary white floor without the earlier
horizontal stripes. The sparse LOW ray pattern still produces unnaturally sharp,
limited light patches: this is NOT visual acceptance of full lighting.

Resize 960x540 -> 1024x576 -> 960x540 retired/recreated both material and lighting
targets; ACTIVE was logged at 13:52:51 and 13:52:53. Removing the two 30-block
temporary sources removed their surfaces and coloured illumination (raw screenshot
`/tmp/lumen-material-rgb-removed.png`). The 208-block test floor was restored to
grass. Final world exit retired resources at 13:54:14, Vulkan workers quiesced at
13:54:21, and `runClient` exited 0. No Raster/Vulkan error was logged; the existing
missing OpenAL device error and Realms/end-of-frame warnings remain unrelated.
Log: `/tmp/lumen-material-rgb-centered-runtime.log`.

The preceding slope-only probe also verified Raster -> Pure restores native sky,
terrain and lighting and retires Raster targets (13:48:02), then returning to
Raster creates new capture resources. That world advanced to daytime; its Pure
screenshot is lifecycle evidence, not a matched lighting/FPS comparison.
Log: `/tmp/lumen-material-rgb-slope-runtime.log`.

Screenshots: `build/reports/raster-material/rgb-material-preview-960.png` and
`rgb-material-preview-1024.png`. Sky/entities are deliberately missing in these
opaque diagnostics. Cutout/translucent coverage, resource-reload acceptance,
Apple/MoltenVK and broader independent-lighting review remain open; no release,
commit, push or upload was performed.

### Stage 2a candidate: developer-only opaque material capture

`-Dtotem.lumen.rasterMaterialCapture=true` enables a separate native raster replay
only while `RASTER_RAY` is selected. `-Dtotem.lumen.rasterMaterialPreview=true`
additionally replaces the visible scene with that diagnostic buffer. Both flags
default off; preview alone does nothing. This is NOT an independent-lighting mode.

The pass reuses this frame's native opaque/cutout draw lists, atlas, projection,
vertex/index buffers and depth rules. It runs after native passes close and clears
its references at frame boundaries. It owns separate colour/depth targets (at most
4096*2160 pixels); other profiles/world exit retire those targets. No mutable world
is read during command recording. No original lightmap, mesh or gameplay brightness
is modified. The normal image is untouched unless explicit preview is requested.

Its shaders omit lightmap and fog but retain native vertex colour, INCLUDING baked
AO/directional shade and biome tint. Thus the buffer is **not pure unlit albedo**.
It has a coverage alpha and its own depth, but no normals/material classification,
entities, fluids, translucent surfaces or matching final-scene coverage contract.
Black preview regions mean absent opaque geometry, not missing light. Native draw
visibility is reused: this is not unlimited-world rendering. No production lighting
consumer uses this buffer yet; RGB source rules from stage 1 remain unchanged.

Stage 2a validation: Java 25 offline `build` passed (197 tests, zero failures/errors),
including eight solid/cutout x separate/multidraw x vertex/fragment shader variants,
pipeline construction and configured-game mixin ABI checks. Pipeline layout checks
compare sets: RenderPearl's builder does not promise layout-list iteration order.
Runtime caught and safely disabled an invalid shader identifier in the first probe;
that issue is corrected. The second native Vulkan probe reached ACTIVE and produced
the expected no-lightmap/no-fog buffer. Raw 960x540 screenshots were inspected at
native scale under the art-direction workflow: textured ground, near blue wall and
far orange wall visible; sky and entities absent by design. Pure restored the native
night scene and retired capture resources at 11:35:25; rejoining with Raster rebuilt
the capture at 11:37:13. Resize 960x540 -> 1024x576 -> 960x540 retired/recreated
targets successfully. Final world exit retired capture targets at 11:38:38; Vulkan
workers quiesced at 11:38:43 and `runClient` exited successfully. Logs:
`/tmp/lumen-material-runtime-fixed.log` and
`/tmp/lumen-material-validated-build.log`.

No comparable FPS benchmark was run for this stage. Developer material preview is
not production lighting and any preview frame metrics must not be compared as a
normal profile benchmark. Independent review, Apple/MoltenVK, dedicated-server
runtime, cutout visual acceptance and full RGB lighting remain pending. This does
not supersede the historical stage-1 performance results below. No release implied.
Stage 2a build JAR SHA-256:
`e64767918bc447a042608e103a6761d71797dd613ab21a26886bb7bcc5b3d01e`.

The shader still corrects already-lit native colour within 24 blocks. It is NOT
an independent material/light buffer and does NOT remove vanilla light. Far
geometry remains native. RGB source parity is not propagation or pixel parity:
ray hit emission and voxel RGB spreading have different transport/attenuation.
The current correction cannot properly illuminate black native pixels. There is
no new direct-light pass, sun shadow, transmission/refraction, PBR or far GI.

## Next independently verifiable stages

1. Capture unlit base colour, geometric/material normals, emissive contribution,
   surface class and depth before fog/transparency composition. Validate opaque,
   cutout, entity and fluid ordering separately; do not divide final lit colour
   by an estimated lightmap or globally force full-bright.
2. Add direct/environment/RGB lighting over that surface buffer. Snapshot source
   tuning outside GPU recording. Reuse RGB colour/strength rules, but explicitly
   specify ray-distance attenuation instead of pretending voxel propagation is
   physically identical to secondary rays.
3. Implement bounded coarse-level extraction/upload, unknown-cell handling,
   thin-wall conservative coverage, transition blending and far lighting. The
   coverage policy alone is not evidence this stage exists.
4. Switch off vanilla visual light only after matching-frame independent inputs
   and output are ready. Failure/profile switch/resize/reload must restore native
   lighting without leaving full-bright meshes or stale bindings behind.

## Validation status

Target: Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.160.5+26.3, Java 25.
Java 25 via the sibling TotemCore Gradle wrapper (same configured 9.6.1 distribution;
Lumen has no wrapper) completed offline `build`: 195 tests, zero failures/errors,
client compilation, packaged shaders and owning shader/mixin checks passed.
`git diff --check` passed. Impact is Lumen-only; the RGB predictor and overlay
consumers of the stateless source helper retain their existing behavior.

Native Vulkan client: selected Raster reached ACTIVE, world-coordinate movement
retained 144 sections for a two-axis transition and 180 for the axial return.
Placed and removed a temporary soul lantern in the isolated test world. This
proves event execution and continued rendering, NOT final RGB visual parity.
Switching Raster -> Pure retired Raster scene/GPU resources. Pure -> RGB -> Totem
-> Raster was exercised. World rejoin rebuilt with `retainedSections=0` and reached
ACTIVE at 10:40:13. Final leave retired scene/resources on the Render thread;
shutdown quiesced Vulkan workers and exited successfully at 10:41:40.
Raw window captures were inspected at native 960x540 scale under the Totem art
direction workflow; geometry remained visible, with native nighttime lighting.
This is lifecycle/visibility evidence, not independent-lighting visual acceptance.

### One-window performance sample (2026-10-04)

Linux, Xeon E-2236, RTX 5060 Ti (16,311 MiB), system RAM 64,208 MiB.
Java 25: heap maximum 16 GiB, committed 896 MiB during these samples.
960x540, view/simulation distance 12, UI Unlimited (`maxFps=260`), VSync off.
Same camera and frozen nighttime test world; 20 s warmup + 60 s frame-start
interval sample per profile. Raster/Totem use LOW internal resolution and GI;
Totem ray distance 32, reflections off; Raster correction remains 24 blocks.

| Profile | Mean FPS | 1% low FPS | Heap used at sample end (MiB) |
| --- | ---: | ---: | ---: |
| Minecraft Pure | 313.04 | 103.38 | 437 |
| Minecraft RGB | 316.70 | 97.91 | 534 |
| Totem Lumen | 25.16 | 11.53 | 608 |
| Raster Ray | 310.42 | 115.18 | 402 |

These are frame-start intervals, not GPU-completion rates. Profiles have different
effects/quality; heap samples are not peaks or isolated per-profile allocations.
The world was frozen to stabilize the comparison, so these are NOT gameplay or
movement benchmarks. No matched old/new build A/B was run; no performance gain
is established. Existing audio-device and missing end-of-frame effect warnings
occurred; integrated-server lag warnings occurred before freezing the test world.

Apple Silicon/MoltenVK, independent review, dedicated-server runtime and RGB visual
acceptance remain pending. No release, commit or push is implied. Candidate JAR
SHA-256: `940a780fb583c8683a1a0093594834a5ec3309712a22860a0a2de34683757209`.
