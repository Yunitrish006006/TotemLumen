# Gameplay RGB numerical acceptance — 2026-09-29

## Scope

Minecraft 26.3, Java 25, Fabric Loader 0.19.5, Fabric API 0.160.5+26.3,
Totem Lumen Alpha 61. All world changes used isolated local dedicated-server
worlds or their copies. The existing player world was not edited. The server
field was read from its saved gzip warm cache with
`scripts/inspect-gameplay-light-cache.mjs`. This parser checks the cache header,
source index and 4,096-cell section payloads, then reports requested block
coordinates and section SHA-256 hashes. Values below are packed RGBA gameplay
light, where the high nibble is the light level.

## Numerical matrix

| Case | Observation | Result |
| --- | --- | --- |
| Placement and x/z/y boundaries | A torch at `(-1,80,-1)` saved as `e5af`; its four horizontal face neighbors and the block above were `d5af`; the stone below was `0`; five blocks away along the open ray was `85af`. The light crossed x=0, z=0 and y=80 section boundaries. A second torch at `(255,80,255)` produced `e5af` at its source, `d5af` across x=256 and z=256, `b5af` at `(256,80,256)`, and `0` in the stone below. | Pass |
| Settled saves and restart | Two settled saves of the first torch had identical queried section hashes. At `(0,80,0)`, the source and all queried samples and four section hashes matched exactly before rule reload and after a server restart, despite revision changing from 228 to 297. | Pass |
| Remove and immediate save | Removing `(-1,80,-1)` then immediately saving cleared the source index, while the cache still held the previous committed field (`e5af` at the former source). A later save after queued propagation had `0` at the source and all queried neighbors. This is the expected provisional-cache interval; the immediate save alone is not a settled field. | Pass after convergence |
| Actual `/reload` rule change | A test data pack changed `minecraft:torch` to blue. Reload queued one Overworld rebuild; source changed from `e5af` to `ef00` and face neighbors from `d5af` to `df00`. Disabling the pack restored the original source, neighbor values and all four queried section hashes. A reload with unchanged rules queued zero rebuilds. | Pass |
| Offline emitter removal | An unmodified Minecraft 26.3 server removed `(0,80,0)` from a world copy while Totem Lumen was absent. The copy was then opened with a *valid old warm cache* containing that torch (`2,118` sources, `206` sections loaded). After live chunk reconciliation, the source index, former source, five neighboring cells and a five-block ray all saved as `0`. | Pass |
| Cold chunk load order | Two copies of the same world started without an Overworld warm cache. In A→B, the force-load command for chunk `(15,15)` containing the torch preceded the three neighbor commands by ten seconds. In B→A, the three neighbor commands preceded `(15,15)` by ten seconds. Both settled twice with unchanged revision (352 and 347 respectively). Their source value, seven queried samples, and five section hashes matched exactly. | Pass |
| Dedicated server/client field parity | A 26.3 development client connected to the loopback dedicated server. Its received revision 752 section values were `e5af` at `(255,80,255)`, `d5af` across x, `d5af` across z, and `b5af` diagonally across both boundaries. These match the server's saved field at the same positions. | Pass |

The five cold-load section SHA-256 values, in section order `(15,5,15)`,
`(16,5,15)`, `(15,5,16)`, `(16,5,16)`, `(15,4,15)`, were:

```text
5efc71b39d0d2a4970dc2ec1ae51985d83a357e298e33ccfe2246d8693976b85
54be03355afbe639dca6b6070132ec0c6faf4d512b7a9d99e516a2024b5c321e
f0718aa712e76667bf97173139a59b944a5d88837ec05ff1d5bd51ae692adf3a
dd09791ca0caf9bc04fcfed787f8f11e26b0d619585af53c34c0e73b3aeb1cf5
0c8135a0a074f7e2c1e632916aa4f9906f8cf4de154e030bc1efb54eee9e14fa
```

## Validation boundary

`gradle --offline check` passed before and after the runtime matrix, including the unit
tests, RGB hot-path and section-refresh verifiers, daylight-balance verifier,
mixin descriptor checks and shader compilation. The client/server comparison
asserts the received RGB *field*, not rendered pixel equality. The player's
six Minecraft RGB visual checks and smooth sky-light confirmation are recorded
in `GAMEPLAY_RGB_RUNTIME_2026-09-28.md`. Matched frame-time and response-latency
measurements, large-world convergence at scale, and broader Totem renderer
coverage remain separate performance and rendering gates.
The force-load commands were issued and acknowledged in the stated order;
individual Minecraft chunk-load callbacks were not timestamped.

The client readout was a temporary, opt-in diagnostic and was removed after
the comparison. The local test server's temporary loopback-only account and
whitelist changes were restored. No test world or generated cache was added
to the repository.
