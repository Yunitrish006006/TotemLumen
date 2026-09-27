# Server Gameplay Lighting Roadmap

This roadmap tracks the server-authoritative gameplay-lighting subsystem separately from the Vulkan renderer roadmap in `ROADMAP.md`.

## GL0 — Server-owned lighting semantics

Status: **completed in Alpha 31 / extended in Alpha 32**

- World data-pack block lighting rules.
- Server-owned `emission_color`.
- Join and successful `/reload` synchronization to capable Totem Lumen clients.
- Alpha 32 adds optional server-only `gameplay_strength`.

## GL1 — Deterministic packed RGB gameplay field

Status: **implemented; runtime validation pending**

| Decision | Baseline |
| --- | --- |
| Spatial unit | `16 x 16 x 16` output core |
| Rebuild read area | output core + 15-block halo |
| Voxel storage | packed 16-bit RGB |
| Channels | 4 bits each, `0..15` |
| Combination | component-wise maximum |
| Propagation | deterministic six-neighbor Minecraft-like propagation |
| Minimum falloff | 1 level / block |
| Maximum influence | 15 blocks |
| Empty section allocation | none |
| Commit policy | staged solve, core-only write |
| Chunk readiness | loaded halo chunks must finish source scanning before solve |
| Stale-result guard | per-chunk generation/load/readiness snapshot |

Implemented pieces:

- sparse vanilla-emissive source index;
- budgeted chunk source scans with `SCANNING` / `READY` state;
- deterministic source ordering;
- primitive FIFO propagation queue containing position + packed RGB only;
- core/halo rebuild isolation;
- per-chunk input generation guards;
- dirty/convergence tracking;
- periodic timings and counters.

The settled field must depend only on world state and lighting rules. Source discovery order, chunk load order and per-tick work budget may change completion time but must not change the final RGB field.

## GL1.5 — Server-authoritative client field

Status: **implemented; runtime validation pending**

- The server publishes only stable RGB sections.
- Initial join performs a bounded full section sync.
- Subsequent stable changes are sent as bounded section deltas.
- Every payload carries a monotonic field revision and stale revisions are rejected client-side.
- Client prediction remains available for immediate visual response.
- A delivered server section removes the corresponding prediction override; untouched client cells fall back to authoritative server data.

## GL1.6 — Balanced reload persistence

Status: **implemented; runtime validation pending**

Persistence is a warm cache, not authoritative world state.

Stored per dimension:

- indexed gameplay RGB sources;
- stable RGB section values;
- field revision;
- format version;
- deterministic solver version;
- effective lighting-rule hash.

Reload behavior:

1. validate cache format, solver version and lighting-rule hash;
2. stage cached data by chunk without force-loading chunks;
3. install warm source/section data when that chunk loads;
4. expose validated warm sections immediately to avoid a dark reload flash;
5. run normal source scans and deterministic core/halo rebuilds;
6. replace provisional warm sections with reconciled solver output.

Corrupt, oversized, solver-incompatible or rule-incompatible caches are discarded. Cache replacement is atomic where supported.

## GL2 — Hostile spawning integration

Status: **implemented in Alpha 32; runtime validation pending**

- Replace only the hostile dark-enough light predicate, preserving the rest of Vanilla spawn-rule flow.
- Keep Vanilla sky/dimension thresholds.
- Read Totem Lumen RGB block light in O(1) for stable sections.
- Reject natural hostile dark-spawn checks in dirty sections until convergence.
- Apply per-entity RGB spectral sensitivity.
- Built-in Nether-mob profile reduces red-light sensitivity.

## GL3 — Dimension and temporal semantics

Status: **baseline implemented; extensions deferred**

Current:

- visual flicker uses one stable gameplay intensity instead of per-tick relighting;
- daylight/sky remains a cheap query-time term rather than RGB-cache rebuild work;
- optional dimension environment RGB can affect spawning without being stored in every voxel.

Deferred:

- gameplay-significant pulsing lights with cached spatial contribution + dynamic scalar state;
- custom daylight curves per dimension;
- weather-specific spectral environment rules.

## GL4 — Profiling and scale validation

Status: **next validation gate**

| Scenario | Acceptance target |
| --- | --- |
| Idle loaded world | < 0.2 ms/tick additional lighting work |
| Normal play | < 0.75 ms/tick |
| Heavy lighting changes | < 1.5 ms/tick typical |
| Hard lighting processing ceiling | ~2.0 ms/tick server-wide |
| Dirty backlog | safe: may suppress hostile spawns, never permits stale-dark spawns |
| Dedicated server | no client/Vulkan class initialization |

Required tests:

1. same saved world loaded repeatedly produces the same settled field hash;
2. source-order shuffle produces the same field hash;
3. A→B and B→A chunk load order produce the same field hash;
4. different per-tick work budgets produce the same settled field hash;
5. repeated light placement/removal at section and chunk boundaries;
6. four-chunk intersection propagation;
7. chunk unload/reload rejects stale jobs;
8. warm reload shows cached light immediately then reconciles without incorrect chunk flashes;
9. `/reload` invalidates incompatible warm data and changes RGB/gameplay strength correctly;
10. dedicated server/client stable-field parity;
11. long-running memory/source-count stability.

## GL5 — Production hardening

Status: **planned after profiling**

Potential work, only if measurements justify it:

- profile-driven sparse/compressed section persistence if disk/RAM measurements justify it;
- selective data-pack reload invalidation by affected block IDs;
- more compact source/index maps;
- configurable server budgets;
- administration/debug commands for RGB value, dirty status, field revision and queue depth;
- compatibility hooks for other server mods that alter hostile-spawn rules.

## Non-goals

The server subsystem will not become a second renderer. The following remain client-only unless a future gameplay mechanic explicitly requires a cheap deterministic approximation:

- GI/light bounce;
- reflection/refraction;
- temporal accumulation/denoise;
- volumetrics;
- physically correct sun shadows;
- visual animation/flicker;
- Vulkan compute or hardware ray tracing.
