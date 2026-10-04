# LP-1 isolated worker shutdown probe — 2026-10-03

The research harness now exercises the production `VulkanWorkerLifetime` gate
while an isolated worker creates a real Vulkan compute pipeline. This closes a
test-coverage gap between the existing Java lifetime tests and the previous
Minecraft zero-worker shutdown smoke. It does **not** complete LP-1 Minecraft
runtime acceptance.

## What the probe establishes

- A device lease is reserved before starting the worker; shutdown rejects new leases.
- The worker waits until the shutdown caller is observed waiting before entering
  native creation. Thus even a fast cache hit overlaps the shutdown wait.
- Completion is sampled immediately when the gate returns, before defensive
  waiting or thread joining can conceal an early return.
- Ordinary and pre-interrupted shutdown retain that ordering. The interrupt flag
  is recorded, then cleared by the standalone harness before report/cache I/O.
- The harness destroys its pipeline, cache, shader/layouts and device only after
  the worker has finished. The runner requires successful creation, gate evidence,
  returned device destruction and a zero process exit code.
- Timeout still kills only the supervisor's isolated process group. It does not
  call device destruction concurrently with a native compiler or target a game process.

`verifyWorkerShutdownResearch` checks successful and throwing work, each with
ordinary and interrupted shutdown. A fifth, negative control deliberately skips
the first gate wait and must be rejected, while defensive cleanup still waits.
The tool is in the research source set and is excluded from the player JAR.
Production rendering algorithms and lifetime implementations are unchanged by
this probe addition.

## Native evidence

Linux x86-64, Java 25.0.3+9, RTX 5060 Ti / NVIDIA 595.84, the existing isolated
Vulkan research device configuration. No Minecraft instance, surface or shader
dispatch is involved; device features are not equivalent to Minecraft's device.

| Shader / application cache | Shutdown | Native create | Gate through worker join | Outcome |
| --- | --- | ---: | ---: | --- |
| bootstrap / empty | ordinary | 24.235 ms | 25.456 ms | passed |
| bootstrap / empty | interrupted | 15.354 ms | 16.750 ms | passed, interrupt restored |
| reflection / imported seed | ordinary | 25.752 ms | 27.180 ms | passed |
| reflection / imported seed | interrupted | 34.184 ms | 36.100 ms | passed, interrupt restored |

All four recorded one outstanding lease at shutdown, refusal of new work,
observed waiting, work completion before gate return, worker termination before
cleanup, `VK_SUCCESS`, device destruction and exit code 0. Reflection feedback
reported application-cache hits; bootstrap feedback reported misses. Empty
application cache does not establish a cold driver cache. These timings are
single observations, not performance comparisons or FPS measurements.

Reports are under ignored `build/reports/compile-research/`:
`lp1-bootstrap-{wait,interrupt}-20261003/` and
`lp1-reflection-{wait,interrupt}-20261003/`. Each contains provenance, started,
native result, process log and runner result. Provenance includes the actual
compiled production lifetime classes as well as client/research bytecode and
the export/shader identities. Existing cache seeds are read without modification.

Java 25 / Gradle 9.6.1 `--offline build verifyWorkerShutdownResearch --no-daemon`
passed. The unchanged 152-test result was reused by Gradle (`test UP-TO-DATE`);
shader and mixin verifiers ran successfully. After strengthening the probe's
early-return check and adding its negative control, the isolated verification
task was rebuilt and passed before all four native runs. The development JAR
SHA-512 remained unchanged, and contains no research classes.

## Reproduction

Use Java 25 and the repository's documented Gradle 9.6.1 installation (the
repository does not yet contain a wrapper). First prepare a fresh export:

```sh
gradle --offline exportCompileResearch verifyWorkerShutdownResearch -PresearchOutput=build/reports/compile-research/lp1-export --no-daemon
python3 scripts/run-compile-research.py --export build/reports/compile-research/lp1-export --shader bootstrap --output build/reports/compile-research/lp1-bootstrap-wait --device 'RTX 5060 Ti' --seconds 120 --shutdown-probe wait
python3 scripts/run-compile-research.py --export build/reports/compile-research/lp1-export --shader bootstrap --output build/reports/compile-research/lp1-bootstrap-interrupt --device 'RTX 5060 Ti' --seconds 120 --shutdown-probe interrupt
```

Set `JAVA_HOME` or pass `--java` to the runner. Select the actual unique GPU name
on the test machine; the runner must never silently select software Vulkan.
Every output directory must be new. For the reflection check, use
`--shader reflection --cache-mode import --cache <matching-research-seed-directory>`.
A missing or incompatible seed is not permission to transplant a game cache.
Omitting `--shutdown-probe` retains the original synchronous compile experiment.

## Remaining gates

This deliberately starts native creation after shutdown has begun, using a
previously reserved worker lease. It does not test the full Minecraft
`VulkanDevice.close()` mixin, prepared-pipeline publication, descriptor retirement,
in-flight GPU dispatch, or a long-running full shader compilation at game exit.
The existing LP-1 requirements remain: full-lighting readiness, cold-compile
game exit, warm exit with in-flight work, resize, reflection toggles and world
exit/re-entry, followed by Apple Silicon/MoltenVK validation. No new Mac,
dedicated-server, image, FPS or gameplay-light acceptance is claimed here.
