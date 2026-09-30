# Minecraft RGB performance follow-up — 2026-09-30

## Setup

Minecraft 26.3, Java 25, Fabric Loader 0.19.5, Fabric API 0.160.5+26.3,
Apple M4, render distance 16. The development client used the isolated
`CodexRgbRuntimeCopy` world. The renderer was switched between
`MINECRAFT_RGB` and `MINECRAFT_PURE` for a stationary torch-room comparison.
The test copy, not the original player world, was saved after testing.

An opt-in `-Dtotem.lumen.rgb.frameMetrics=true` diagnostic records 600-frame
windows after 200 warmup frames. It measures wall-clock intervals between
`Minecraft.renderFrame` starts and time spent in that method. These are CPU
wall-clock timings, not GPU timestamps or input-to-present latency. The
diagnostic is off in normal launches.

## Stationary foreground frame windows

| Profile | Window starts | Interval p50 (ms) | Interval p95 (ms) | Render p95 (ms) |
| --- | --- | --- | --- | --- |
| Minecraft RGB | 18:17:23, :31, :39 | 13.10–13.18 | 14.75–14.79 | 13.59–13.69 |
| Minecraft Pure | 18:24:24, :31, :38 | 12.87–12.96 | 14.88–14.98 | 13.72–13.76 |

These are sequential runs in the same view, resolution and render distance;
world time and background computer activity were not frozen. The RGB p95
intervals were about 0.1–0.2 ms lower in these windows; this scene did not
show a meaningful RGB p95 regression.
It is not a controlled equivalence result or a new-chunk flight result.
Windows after focus changed are excluded: macOS/Minecraft throttled the
background window, producing near-33 ms intervals in both profiles.

## Large-world initial convergence

The client copy had about 5,000 allocated RGB sections and 60,000 indexed
sources near the player. A baseline run scanned every non-air server section.
The follow-up skips a section when its block palette contains no emitting
state, while the scan completion still reconciles stale warm-cache sources.

| Run | Early pending scans | Pending scans after about two minutes | Near-drained dirty sections |
| --- | --- | --- | --- |
| Baseline, entered around 16:39 | 1,172 at 16:39:31 | 723 at 16:41:01 | 23 at 16:46:29 |
| Palette skip, entered around 18:34 | 818 at 18:35:04 | 0 at 18:36:04 | 12 at 18:38:35 |

The queue drained sooner in this run, but the world ticked between runs and
the warm-cache contents differed, so these counts establish a directional
improvement rather than a controlled speedup. During the optimized active
windows, gameplay-light processing averaged about 1.9–2.1 ms per measured
tick and individual maxima reached 7.9 ms. The documented GL4 heavy-load
target is not yet demonstrated; the time budget is checked between work
slices and can be exceeded within a slice.

## Correctness after the palette skip

The separate `CodexRgbDedicatedProbe` world was restarted on a 26.3 dedicated
server with the change. At `(1,80,0)`, removing the test torch and allowing
the field to settle cleared its source index and the source/east/south samples
to `0`. Replacing the torch restored source `e5af`, east and south `d5af`,
solid floor `0`, and the two queried section SHA-256 hashes exactly to their
pre-removal values. `save-all flush` was used before each cache inspection.
The server was stopped through its console. `gradle --offline check` passed.

## Remaining acceptance

- Measure block edit to first visible updated frame, ideally with a synchronized
  client/server trace. The cache checks establish settled field correctness,
  not response latency.
- Compare new-chunk movement under Pure and RGB in a repeatable path. The
  stationary room and mixed JFR samples do not close the chunk-flight gate.
- Profile GL4 with repeatable heavy-load scenarios and enforce or revise its
  tick-cost target. The current trace only characterizes this world load.

The September 29 numerical matrix and player visual checks remain the RGB
correctness evidence; this report adds a limited performance comparison.
