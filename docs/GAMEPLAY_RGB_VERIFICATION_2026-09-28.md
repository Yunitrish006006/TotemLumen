# Gameplay RGB verification — 2026-09-28

This record checks the four open correctness PRs (#59–#62) against the current
Minecraft 26.3 source. It is not an in-world acceptance result.

## Repository and remote state

- Verification base: `fix/gameplay-light-warm-persistence` at
  `bc9573ba452c05d633af924ad4f7561d0fe13aba`.
- GitHub reported #59–#62 open and mergeable, with their latest `build` checks
  successful when checked on 2026-09-28.
- #60 ends at `d578c20`; #61 branches before that commit and carries the source
  index helper change independently as `1197320`. A synthetic merge of those
  heads was clean, but the heads are not a strict ancestor chain.

## Checks performed

- `gradle clean build` with Gradle 9.6.1 and Java 25: passed, including JUnit,
  RGB hot-path and section-refresh verifiers, mixin descriptors, and shader checks.
- `gradle runClient`: Minecraft 26.3 and the Vulkan device initialized on Apple
  Silicon. No world-entry event was observed in the runtime log before the
  client was stopped, so the runtime matrix below remains open.
- Source inspection found a 15-block read halo, core-only publication,
  loaded-halo source readiness checks, generation-based stale-job rejection,
  server section revision filtering, provisional warm sections, and live-scan
  removal of cached emitters absent from the chunk.

## Correctness finding and local fix

Before this fix, an in-flight client rebuild could publish a local section
after a server packet cleared that section's prediction. The local result would
then take precedence over the server value until another server packet arrived.

The local fix records which sections have received server data. A local world
change may temporarily predict its affected sections, and the next server
packet retires that prediction. If no packet arrives, the prediction expires
after 200 client ticks and the server section is restored. Full sync also
refreshes meshes for sections it removes. The build-time section-refresh
verifier now exercises late predictor publication, immediate local prediction,
server replacement, expiration, and full-sync removal.

## Open runtime acceptance

Run the handoff matrix on this exact fix: repeat world reloads; A→B and B→A
chunk loading; x/z/y boundaries and four-chunk crossings; source add/remove
and immediate save; lighting-rule `/reload`; offline emitter removal; and a
dedicated server/client comparison. Compare settled RGB values, not only
screenshots. Until these checks pass, do not merge #59–#62.

The reported slow lighting remains unprofiled on this branch. Source confirms
the fixed 2 ms client predictor budget, 32 mesh-dirty sections per tick, 8+1
scene snapshots per extraction, full-field GPU snapshot cloning/sorting, and
overlay rebuild debounce. Those are candidates, not measured causes. Measure
convergence latency and frame cost separately before changing budgets or
upload behavior. The Totem render profile also sets `sceneUploadRequired` and
invalidates temporal history whenever the RGB field revision changes; its
per-update cost has not yet been measured.
