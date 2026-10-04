# Opt-in shared filtered trace runtime

Status: development integration, **not the default and not release acceptance**.

The `shared-filtered-trace` candidate is now selectable in the actual P12 full
lighting and P16 reflection prewarm paths. Both use one process-fixed selector;
restart to change it. Bootstrap and the default variant selection are unchanged. The
research exporter delegates to the same transformation, preventing two drifting
implementations. The initial integration did not change the scene ABI; the
block-tint follow-up below deliberately changes the internal material layout
in both baseline and candidate. Quality settings, samples and distances remain unchanged.

## Block-tint follow-up — 2026-10-03

Source inspection found that static block extraction discarded the quad tint
index, and the shared P18 material resolver sampled raw albedo without the
Minecraft block/biome tint. This missing data path exists in baseline as well
as candidate; it is not introduced by the filtered-trace transform. It explains
missing grass coloration, but does not by itself establish the cause of every
blue-grey/noisy pixel in the earlier image.

The extractor now resolves `BlockColors.getTintSource(...).colorInWorld(...)`
while world data is available. Only immutable RGB travels in `QuadSurface`;
negative/unavailable tint indices remain white, and tint alpha is ignored.
Canonical cube UV conversion preserves it, and registry equality includes it
so distinct biome colors cannot alias the same material. Cube surface ABI v2
uses ten words per face; model quads use 22 words, with a shared CPU/GLSL stride.
Both full lighting and reflection multiply textured albedo by the captured tint.
Neither texture alpha coverage nor server RGB lighting is changed.

The existing bounded registries still cap IDs at 4095 and mesh quads at 65536.
Additional biome-color variants consume these existing budgets; wide-biome
capacity/fallback behavior needs runtime coverage. Per-vertex custom model
colors and tinting of untextured fallback geometry are not implemented here.

Validation so far: Java 25 / Gradle 9.6.1 offline build passed in 54 s with
163 unit tests, production mesh-packing/cube-canonicalization checks, existing
mixin/RGB checks, and baseline/candidate shader compilation and routing checks.
New tests cover neutral/black tint, alpha-independent identity, color-separated
deduplication, all cube faces including the last legal record, and adjacent-field
integrity. No shader/runtime visual equivalence claim follows from these tests.

The deliberate material ABI/source revision has new candidate golden hashes:

- full: `66869b9374362efb6878a1446e838b0ff89293288eea8ba7400823aa84e05c06`
- reflection: `f823a2ff9238219d47e942ddd85391ee806ffaa19c188fdadab693e0c3d6b5f9`

Export: `build/reports/compile-research/tint-20261003`. The filtered-trace
transformation itself is unchanged. Prior CR2 and in-game cold/warm timings below
belong to the older hashes, not to these binaries. Native desktop evidence for
the tint revision is recorded below; Apple Silicon/MoltenVK and independent review
remain pending. The independent review has not been replaced with self-review.

The revised dedicated-server load completed successfully at the expected
unaccepted-EULA gate (`tint-server-smoke.log` in the isolated test directory).
This is a load/isolation check, not server-world validation. The in-world client
logged `minecraft:grass_block`, `minecraft:block/grass_block_top`, tint index 0,
RGB `0x91bd59`. The disposable world was set to day and tick-frozen for inspection;
the compiling/fallback screenshot is not a successful Lumen frame.

Native RTX 5060 Ti follow-up: full lighting prewarm completed at 16:28:55
(741,512 ms total, 740,804 ms native create), reflection at 16:30:55
(120,078 ms total, 119,911 ms native create). Both scene bindings and P16
multipass reached READY. This launch loaded the prior device cache but used
new shader hashes; feedback was not requested and application cache hit is
unknown. The combined 861,590 ms is not a fully cold-driver benchmark.

`tint-full-ready.png` shows green grass after full-lighting takeover while
reflection was compiling. After unfreezing and setting noon, `tint-noon.png`
and HUD-free `tint-both-ready.png` show the active full/reflection path retaining
green grass. These images live under `/tmp/lumen-candidate-runtime.eY2QOu`.
This establishes a visible tint effect, not overall image acceptance: sky,
brightness, noise, reflective materials, wider biome coverage and a matched
baseline/candidate comparison remain open. Day/noon/freeze differences mean
these screenshots are not a controlled luminance/performance comparison.

Tint-revision development JAR SHA-512:
`4e8c47464b9b4cd1430a68071c5235c74a5b8522c89e024b2ce4c4622570ce79677cb5e27bfc466fc922e84b30604feeb1b02d97cf559a28f96eff1d5f36a5b2`.
The JAR excludes the research-only tint/variant verifiers.

First tint-revision lifecycle: Save and Quit saved all three dimensions and
returned to title. Quit Game at 16:33:17 logged worker quiescence; the client
launcher exited 0 and Gradle succeeded. Log: `tint-gradle.log`. This is one
normal-close result, not an explanation of the earlier integration run's 143.

Warm restart of the tint revision loaded the same game's cache and retained both
new source hashes. Full prewarm was 684 ms, reflection 375 ms (1,059 ms combined;
not whole-game/world loading and not a repeated controlled benchmark). Re-entering
the same disposable world at 16:35:45 bound both passes and displayed green grass.
Log: `tint-warm-gradle.log`; image: `tint-warm-world.png`.

Warm resize was confirmed at the X window level (960x540 -> 1024x576). Both
passes rebound at 16:37:25 without another pipeline prewarm. Image:
`tint-warm-resize-confirmed.png`. An earlier name-based resize command did not
change the actual window size and is not counted as a passing resize test.

Warm Save and Quit saved all three dimensions, returned to title, and Quit Game
exited 0 with worker-quiescence evidence and Gradle success. Both tint-revision
game runs ended normally; their private displays and the dedicated log watcher
were also closed. The log watcher was deliberately stopped with SIGTERM (143);
that is **not** either game's exit status. No player release/default adoption or
independent review was performed; review authorization is still pending.

## Opt in

With Java 25 and this checkout's documented Gradle 9.6.1 installation (no wrapper
is currently checked in):

```text
gradle runClient -PlumenShaderVariant=shared-filtered-trace
```

For a local development JAR, the equivalent JVM argument is:

```text
-Dtotem.lumen.shaderVariant=shared-filtered-trace
```

Use the `TOTEM_LUMEN` render profile and a disposable test world. Removing the
argument and restarting restores baseline; no persistent player setting is
changed by the selector. Unknown variants and source-anchor drift are rejected,
not silently substituted. If candidate creation fails, existing failure handling
keeps Lumen unavailable; it does not claim a fallback frame as Lumen success.

Logs identify `pass=full` / `pass=reflection`, the selected variant and source
SHA-256. SPIR-V caching already keys by full source and compilation settings;
the opaque Vulkan cache remains keyed by device/driver/UUID, with the driver
responsible for recognizing reusable entries.

## Initial integration validation — 2026-10-03 (before block tint)

Java 25 / Gradle 9.6.1 `--offline build prepareCompileResearch` passed in 42 s:
159 unit tests, existing mixin/RGB checks, baseline and candidate shaderc
compilation, and new default/opt-in routing checks in separate JVMs.

`check` now includes both shader variants. `verifyDefaultShaderVariant` leaves
the property absent to test the actual default; `verifyOptInShaderVariant`
selects the candidate. They verify restart-fixed selection, both runtime source
builders, rejection of unknown variants, missing/duplicate anchors, clamp drift
and repeated application. Candidate GLSL matches the exact CR2 research output:

- full: `46283b8a3b3b14ccd4b14254683e44a78c93785926d854c20e50400e0c8b9725`
- reflection: `d664c63448f36f9eecbc2989638f464e367798d1bfd7d4cf0c8ca225c17faff4`

Golden hashes are drift alarms, not permission to accept future behavior changes
by updating expected values. Neither these checks nor successful compilation
prove rendered-image equivalence, frame rate or native lifecycle safety.

Dedicated-server loading was repeated after this integration: Minecraft 26.3 /
Loader 0.19.5 / Fabric API 0.160.5+26.3 registered Lumen's server gameplay
entrypoint and stopped at the expected, unaccepted EULA gate. Gradle completed
successfully in 24 s. This checks module loading, not a running server world.
Log: `/tmp/lumen-candidate-runtime.eY2QOu/server-smoke.log`.

Local development JAR: `build/libs/totem-lumen-0.1.0-alpha.61.jar` (unpublished).
SHA-512: `2ec164d940746be62cfc3b310a108ea2c66ba138a7d03f052c091aacabe7e07e44391d226e8b87e5a9d854fc2f221b9ffcc0962a3d65ccd86e0d4b721970c600`.
The JAR contains the client selector/patch but not the research verifier.

## Runtime evidence and remaining gates

Native desktop testing uses RTX 5060 Ti / NVIDIA 595.84 and an Xvfb display.
The first launch's init-script run directory was overridden by the build's
client configuration. It reached the menu in the existing development directory,
never opened a world, and received Quit Game. Its log showed an outstanding
compiler worker at shutdown; the launcher later returned 143 without a complete
shutdown record. This is not a passing close test.

The corrected init script applies the run-directory override after evaluation;
`/proc` confirmed `/tmp/lumen-candidate-runtime.eY2QOu/run`. The game logged
`pass=full, variant=shared-filtered-trace` with the expected source hash and
started actual driver compilation. A deliberately copied research cache seed
was **not** used: the game reports a different cache UUID and logs SESSION EMPTY.
Do not describe this run as a warm-cache timing result.

The corrected client created and entered the disposable flat world
`LumenCandidateTest` (seed 20261003, structures disabled). Logs show the
TOTEM_LUMEN settings and slime geometry capture while reflection readiness is
false. Entering a world while native compilation runs is not full-lighting
acceptance; the normal renderer remains the fallback until readiness.

Logs: `/tmp/lumen-candidate-runtime.eY2QOu/gradle.log` (first launch),
`isolated-gradle.log` and `run/logs/latest.log` (corrected launch).

Required before default adoption: completed in-world full/reflection readiness,
native Vulkan and Apple Silicon/MoltenVK image/lifecycle checks, repeated matched
cold/warm timings, and independent integration review. The earlier 636 s research
compile is not a promised in-game ready time. No publication is authorized here.

### Completed native desktop run

The corrected first run completed in Minecraft (application cache initially
empty for the actual game UUID; driver-internal cache uncontrolled):

| Stage | Native create | Full prewarm including shader/cache work |
| --- | ---: | ---: |
| Candidate full lighting | 745,773 ms | 746,940 ms |
| Candidate reflection | 121,148 ms | 121,289 ms |

Full prewarm started at 14:01:30, completed at 14:13:57, and reflection completed
at 14:15:58 (Taipei, 2026-10-03): roughly 14 m 28 s through both staged prewarms.
Both scene bindings became READY; the render thread logged full lighting and
P16 multipass reflection readiness. A daytime command and screenshots exercised
the live candidate renderer in the disposable flat world. This is basic image
sanity only: a mostly flat ground/sky view does not validate glass, fluids,
entities, complex materials, candidate/baseline equivalence or FPS.

The world was saved and exited, then Quit Game closed the cache and logged
`Vulkan device workers quiesced; backend destruction is safe`. The Gradle client
run exited 0. Preserved log: `cold-client.log`; images include
`day-ready-command.png` and `saved-menu.png` under the test directory above.
The first, misdirected launcher exit 143 remains a separate incomplete result.

### Second launch with the game's own saved cache

The same isolated directory was restarted with the same candidate selection.
The actual game-UUID cache logged SESSION LOADED; the shader source hashes
remained unchanged. Prewarm completed at the menu:

| Stage | Native create | Full prewarm including shader/cache work |
| --- | ---: | ---: |
| Candidate full lighting | 2,140 ms | 2,568 ms |
| Candidate reflection | 493 ms | 660 ms |

The two sequential prewarms sum to 3.228 s. This is **not** total game launch or
world-load time. Minecraft did not request pipeline creation feedback, so
`applicationCacheHit=unknown`; do not replace it with a claimed driver-reported
hit. This is one observed warm restart, not a controlled repeated benchmark or
a promise for other GPUs/MoltenVK. The first run's research seed was unused;
this restart loaded the cache saved by Minecraft itself.

Log: `warm-gradle.log` under the same test directory.

The warm process re-entered `LumenCandidateTest`; full lighting and reflection
both bound successfully. Resizing from 960x540 to 1024x576 briefly restored
fallback while scene resources rebound, then both passes returned to READY
without another pipeline prewarm. Screenshots: `warm-world.png`,
`resized-pause.png` (transitional fallback), and `resized-menu.png`.

**Visual acceptance remains open:** the candidate ground appears blue-grey and
noisy, unlike the green vanilla fallback visible during resize. A matched
baseline-Lumen comparison is needed to determine whether this is an existing
renderer/material issue or candidate regression; no attribution or equivalence
claim is made here. This is a concrete follow-up, not a passing image baseline.

Warm shutdown: Save and Quit saved all three dimensions, followed by Quit Game
and a cache-session CLOSED log. However, the launcher returned **143** without
the complete worker-quiesced/successful-build tail observed on the corrected
first run. Cause is unestablished; do not mark warm shutdown passed. Preserved
log: `warm-client.log`. This outcome does not erase the recorded warm pipeline
completion/re-entry evidence, but remains an explicit lifecycle gate.
