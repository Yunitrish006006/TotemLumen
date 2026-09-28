# Gameplay RGB runtime follow-up — 2026-09-28

## Scope and evidence

Minecraft 26.3, Java 25, Gradle 9.6.1, `MINECRAFT_RGB`, an isolated copy of an
existing large single-player world, and the development client were used. The
world copy contains many pre-existing emitters. The original world was not
modified. These observations are from runtime debug counters and source
inspection; no GPU frame-time or screenshot comparison was captured.

The first run used the local server-authority correction on top of PR #62. The
second run added five-second client counters. Later runs tested the
server-authoritative predictor reduction, near-player prediction, and warm
cache changes. The world state and player actions were not identical, so the
numbers below show workload changes, not a controlled end-to-end latency
benchmark.

## Findings

- The original client predictor scanned loaded chunks independently of the
  server. During the instrumented run it reached over 20,000 pending core
  rebuilds and over 40,000 immediate propagation tasks. Five-second windows
  reported predictor averages around 5–7 ms per client tick at the heaviest
  point, with individual ticks above 20 ms. A large pending rebuild queue also
  made readiness selection expensive even when little useful work completed.
- In that user-assisted run, placing a torch lit only its own cell. The client
  writes the source cell synchronously but had queued its six-direction
  propagation behind the accumulated background tasks. This is a plausible
  cause of the observed result. The local follow-up prioritizes player edits
  over discovered emitters on the client and promotes the affected rebuilds
  and required chunk scans on the server. In the follow-up Minecraft RGB run,
  the player reported that a newly placed torch lit surrounding surfaces.
- A read-only probe of that test world's saved gameplay-light cache found a
  nearby source at (-1078, 72, 2679) with packed RGB `e5af` (intensity 14).
  Its five non-solid face neighbors held `d5af` (intensity 13); the block below
  held zero. The east and north rays still held nonzero light five blocks from
  the source. This independently confirms propagation in the saved server
  RGB field near the player. The cache alone cannot identify which nearby
  source was the newly placed torch or prove the client frame appearance.
- An independent Minecraft 26.3 dedicated-server probe used a fresh,
  force-loaded test chunk with a stone floor and a console-placed torch. The
  original `ServerLevel.sendBlockUpdated` hook missed the torch while that
  empty-server chunk was below `BLOCK_TICKING` status: the block existed, but
  repeated saves had no source at (0, 80, 0). Restarting rescanned the chunk
  and then found `e5af` at the torch with `d5af` in five open face neighbors.
  Minecraft's `Level.setBlock` calls `setBlocksDirty` for an actual state
  change before its conditional `sendBlockUpdated` call. Moving the hook to
  `Level.setBlocksDirty`, guarded to `ServerLevel`, covers this case. With the
  fix, removing the torch cleared its source and nearby field without a
  restart; placing a new torch at (1, 80, 0) produced `e5af` at the source,
  `d5af` in five open face neighbors, and nonzero light five blocks along two
  open directions, again without a restart. This is saved server-field
  evidence, not a rendered-frame or client/server parity check.
- The same world under near-player prediction had no persistent client scan
  and rebuild backlog. On the final restart, the first five-second window
  averaged 0.85 ms per tick, with at most 24 scans and 332 rebuilds; the next
  two windows averaged 13–18 microseconds per tick with both queues empty.
  This is CPU time inside the predictor, not measured FPS or mesh completion.
- The server remained the major first-entry convergence bottleneck. About 30
  seconds after entry it reported 27,806 dirty sections and 1,138 pending
  scans. About 3.5 minutes later it reported 756 dirty sections and 11 scans;
  the queue then drained. Server average gameplay-light work was about
  1.2–2.1 ms per active tick in those 30-second windows. The screen effect of
  this backlog still needs direct visual confirmation.
- In the first user-assisted Save & Quit, the world-local compressed warm
  cache ended at 40 bytes. Its next load reported zero sources and zero
  sections despite the earlier server log reporting thousands. Chunk unload
  removed in-memory sources and sections before the final save. A later
  corrected restart loaded 54,658 sources and 4,613 sections. Repeated short
  restarts exposed two more issues: startup save could replace staged warm
  data with an empty cache, and an unfinished reconcile could discard dirty
  but still useful provisional sections. Both paths were corrected. The final
  immediate restart loaded 54,741 sources and 1,458 sections, and parsing the
  cache after startup returned exactly those counts. The earlier cache had
  already lost other sections before the final correction, so restoration of
  the original 4,613 sections still needs a longer final-version run.
- `VanillaRgbOverlayRenderer` has no active call site. GPU whole-field
  snapshots and `SceneExtractionBridge` belong to the Totem render path, so
  their costs do not explain this Minecraft RGB run. Mesh dirty enqueue time
  was measured; actual mesh rebuild and GPU frame time were not.

## Local changes and validation

- Server stable sections now supersede speculative local sections even if a
  queued client rebuild finishes after the packet. Local prediction expires
  after 200 client ticks if no replacement packet arrives.
- The warm-cache fix retains unloaded chunks' source index and last committed
  RGB sections for the final save, includes staged warm data in early saves,
  and bounds retained unloaded state. Cached values remain provisional on
  reload and must still pass live chunk reconciliation. Rule reload discards
  snapshots calculated with the previous rules.
- The performance branch records predictor time/work, queue depths, and
  section refresh enqueue counts every five seconds in debug logs. Once a
  server field has arrived, the client limits pre-existing source scans to the
  player's nearby chunks. Local block changes still get immediate speculative
  prediction; old work is cleared after the player moves away.
- The server block-change hook now catches changed block states even when the
  affected chunk is loaded but not block-ticking. It still passes only server
  changes to the gameplay-light engine.
- `gradle clean build` passed after the authority fix, the diagnostic change,
  the warm-cache change, and the first predictor reduction. These builds
  include unit tests, RGB hot-path and section-refresh verifiers, mixin
  descriptor checks, and shader verification. The final near-player and warm
  cache version passed a clean build and two runtime starts. The edit-priority
  follow-up also passed a clean build, including a scheduling verifier. The
  user-assisted torch placement check reported surrounding light, and the
  saved field probe above confirmed nearby RGB propagation. A further clean
  build passed after the server hook change, followed by the dedicated-server
  removal and placement probe above.

## Acceptance status

Do not merge PRs #59–#62 yet. Their latest GitHub checks passed and their heads
were mergeable when checked, but the handoff's runtime matrix remains open:
settled-value comparison over repeated reloads; both chunk loading orders;
x/z/y and four-chunk boundaries; post-fix source add/remove and immediate save;
`/reload`; offline emitter removal; and dedicated server/client parity.
One post-fix torch placement visibly lit surrounding surfaces according to
the player, and a separate saved-field probe confirmed nearby propagation.
One dedicated-server source remove/add case passed after the event-hook fix.
Broader visual behavior and frame time remain unconfirmed. Repeated cache
read/write counts are confirmed; settled RGB equality is not.
