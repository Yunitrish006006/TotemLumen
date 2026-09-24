# Server Gameplay Lighting Roadmap

This roadmap tracks the server-authoritative gameplay-lighting subsystem separately from the Vulkan renderer roadmap in `ROADMAP.md`.

## GL0 — Server-owned lighting semantics

Status: **completed in Alpha 31 / extended in Alpha 32**

- World data-pack block lighting rules.
- Server-owned `emission_color`.
- Join and successful `/reload` synchronization to capable Totem Lumen clients.
- Alpha 32 adds optional server-only `gameplay_strength` while preserving the Alpha 31 RGB packet wire format.

## GL1 — Packed RGB gameplay field

Status: **implemented in Alpha 32; runtime validation pending**

| Decision | Baseline |
| --- | --- |
| Spatial unit | `16 x 16 x 16` section |
| Voxel storage | packed 16-bit RGB |
| Channels | 4 bits each, `0..15` |
| Combination | component-wise maximum |
| Minimum falloff | 1 level / block |
| Maximum influence | 15 blocks |
| Empty section allocation | none |
| Persistence | none |

Implemented pieces:

- sparse vanilla-emissive source index;
- budgeted chunk source scans;
- bounded local section relighting;
- primitive propagation queue;
- dirty/convergence tracking;
- periodic timings and counters.

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

1. ordinary exploration/chunk churn;
2. large redstone-lamp oscillator;
3. repeated light placement/removal at section/chunk boundaries;
4. `/reload` changes of RGB and gameplay strength;
5. Nether red/green/blue controlled spawn rooms;
6. chunk unload/reload stale-light checks;
7. long-running memory/source-count stability.

## GL5 — Production hardening

Status: **planned after profiling**

Potential work, only if measurements justify it:

- profile-driven sparse/compressed RGBA sections if RAM dominates; do not discard light-intensity A;
- selective data-pack reload invalidation by affected block IDs;
- more compact source/index maps;
- optional derived-cache persistence keyed by lighting-profile hash;
- configurable server budgets;
- administration/debug commands for RGB value, dirty status and queue depth;
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
