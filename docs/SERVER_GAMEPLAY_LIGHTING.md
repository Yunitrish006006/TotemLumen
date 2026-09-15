# Server-Authoritative Gameplay Lighting

Status: **Alpha 32 implementation baseline**

Totem Lumen separates visual lighting from gameplay lighting. The client Vulkan renderer is free to use ray tracing, GI, temporal effects, flicker and physically richer shading. Gameplay decisions use a deterministic server-owned RGB light field so shader settings, resource packs and client performance cannot change spawning rules.

## Architecture

```text
Server Data Pack
  ├─ block lighting rules
  ├─ entity spectral spawn profiles
  └─ dimension environment rules
          |
          v
ServerGameplayLightEngine (one per ServerLevel)
  ├─ sparse emissive-source index
  ├─ packed RGB section cache
  ├─ budgeted chunk-source scans
  ├─ budgeted local propagation
  └─ dirty/convergence tracking
          |
          v
Gameplay query
  ├─ hostile mob spawning
  └─ future plant/redstone/gameplay systems

Client
  same emission color rules
          |
          v
Vulkan direct light / shadows / GI / temporal / denoise
```

Core rule: **the server decides how bright a location is for gameplay; the client decides how that light is rendered.**

## Accepted lighting model

| Component | Server gameplay treatment | Client visual treatment |
| --- | --- | --- |
| Fixed block light | Cached RGB 0..15 | Vulkan lighting/GI |
| Visual flicker | Stable `gameplay_strength` | Real-time flicker allowed |
| Gameplay-significant dynamic light | Future dynamic state layered over cached spatial data | Real-time |
| Sky/daylight | Vanilla sky visibility/brightness semantics | Directional sun and shadows |
| Dimension environment | Cheap dimension rule evaluated at query time | Rich environment rendering |

The first server implementation deliberately does **not** perform GI, light bounce, HDR accumulation, visual flicker propagation, directional sun-shadow ray tracing, or Vulkan work.

## Block source rules

World data packs define block emissive color and may define a stable gameplay intensity:

```text
data/<block namespace>/totem_lumen/lighting/<block path>.json
```

```json
{
  "emission_color": [1.0, 0.55, 0.22],
  "gameplay_strength": 13
}
```

`gameplay_strength` is an integer from 0 through 15. When omitted, the block state's vanilla `getLightEmission()` value is used. A rule still cannot turn a normally non-emissive block state into a gameplay light source.

A flickering torch therefore has two meanings:

```text
Visual:   client animation may vary every frame
Gameplay: one stable server value, e.g. 13
```

This prevents a temporary visual dip from unexpectedly allowing a hostile mob to spawn next to a torch.

## Packed RGB storage

Each logical voxel has three 4-bit channels:

```text
bits 0..3   red   0..15
bits 4..7   green 0..15
bits 8..11  blue  0..15
bits 12..15 reserved
```

A `16 x 16 x 16` section contains 4096 positions. Alpha 32 uses a `char[4096]`, or **8 KiB per allocated RGB section**. All-zero sections do not allocate the dense array.

### Storage alternatives considered

| Layout | Bytes / section | Advantage | Trade-off |
| --- | ---: | --- | --- |
| Three 4-bit nibble arrays | 6 KiB | Minimum RAM | More packing work / less convenient random access |
| 16-bit packed `char[4096]` | 8 KiB | Simple, fast CPU access; 4 spare bits | 33% more raw light-cache RAM |

Alpha 32 chooses the 16-bit layout because server tick latency is more important than saving 2 KiB per lit section.

### Raw RGB cache RAM forecast

| Allocated RGB sections | 6 KiB theoretical layout | 8 KiB Alpha 32 layout |
| ---: | ---: | ---: |
| 1,000 | 5.86 MiB | 7.81 MiB |
| 5,000 | 29.30 MiB | 39.06 MiB |
| 10,000 | 58.59 MiB | 78.13 MiB |
| 25,000 | 146.48 MiB | 195.31 MiB |
| 50,000 | 292.97 MiB | 390.63 MiB |

These values exclude Java map/source-index overhead. Actual RAM is lower than `all loaded sections x 8 KiB` because all-zero sections are sparse and unallocated.

## Propagation semantics

Server block light is intentionally Minecraft-like rather than physically additive:

```text
source RGB = color * gameplay_strength
combined RGB = component-wise maximum
next voxel = current RGB - attenuation
attenuation >= 1 per step
channel range = 0..15
```

Example:

```text
warm source = (12, 4, 1)
cool source = (2, 8, 11)
overlap     = (12, 8, 11)
```

Because every step loses at least one light level and source strength is capped at 15, a source cannot affect gameplay lighting beyond 15 block steps.

## Local rebuild strategy

A changed block never causes a whole-world relight. Changes are coalesced by their 16³ anchor section.

For one changed section, the rebuild volume is the complete section expanded by 15 blocks in every direction:

```text
46 x up-to-46 x 46
```

At normal world height this is at most **97,336 voxel positions**, intersecting at most 27 sections. The task runs incrementally through:

1. Clear cached RGB inside the local rebuild region.
2. Seed all indexed emissive sources inside the region.
3. Seed stable light entering from the region boundary.
4. Propagate RGB by component-wise maximum.
5. Prune all-zero section arrays.
6. Mark the affected sections stable again.

Multiple block changes in the same anchor section coalesce. If the section changes while its rebuild is already executing, one rerun is queued using the newest world state.

## Chunk loading and unloading

Chunk source discovery is also budgeted. A newly loaded chunk is marked lighting-dirty while non-air sections are scanned incrementally for vanilla-emissive block states. This avoids a large synchronous 16x16xworld-height scan on chunk load.

On unload:

- sparse source entries for that chunk are removed;
- dense RGB sections for that chunk are released;
- neighboring loaded regions affected by removed sources are queued for local relight.

The RGB field is derived state and is **not written to chunk NBT in Alpha 32**.

## Dirty-region spawn safety

Propagation may intentionally span several ticks under load. Reading a stale dark value during that interval could incorrectly permit a hostile spawn immediately after a player places a lamp.

Alpha 32 uses a conservative rule:

```text
queried section is lighting-dirty
    -> hostile dark-enough check returns false
```

Therefore backlog can temporarily reduce natural hostile spawning, but cannot exploit stale darkness to create a spawn that authoritative lighting would forbid.

## Tick budgets and instrumentation

Default server-wide limits, shared across dimensions that currently have pending lighting work:

```text
work budget: 20,000 work units / tick
time budget: 2.0 ms / tick across all dimensions
slice size:  1,024 work units
```

Chunk scanning and light propagation alternate slices when both have work, so initial chunk loading cannot permanently starve relighting.

### Design targets

| Server state | Target extra main-thread cost |
| --- | ---: |
| Idle | < 0.2 ms/tick |
| Normal play | < 0.75 ms/tick |
| Heavy lighting updates | < 1.5 ms/tick |
| Hard processing ceiling | about 2.0 ms/tick server-wide |

A Minecraft tick has a 50 ms budget; 2 ms is 4% of that budget. The engine records and periodically logs allocated RGB sections, indexed sources, dirty sections, pending scans/rebuilds, average work and average/max lighting tick time. These targets remain estimates until runtime profiling is collected.

## Spawn query cost

A stable hostile-spawn query does not propagate light. It performs:

1. one section lookup and packed RGB read;
2. three spectral sensitivity multiplications;
3. optional dimension-environment RGB evaluation;
4. vanilla sky-light and dimension threshold checks.

The intended complexity is **O(1)**. The expensive spatial work occurs only when chunks/light topology change.

## Spectral spawn sensitivity

Brightness for a mob is not physical luminance. It is the strongest channel after the mob's gameplay sensitivity is applied:

```text
effectiveBlock = max(
    R * blockRedSensitivity,
    G * blockGreenSensitivity,
    B * blockBlueSensitivity
)
```

This avoids the undesirable result where a saturated blue room would count as almost dark simply because human luminance weighting gives blue a low coefficient.

Profiles are server data resources:

```text
data/<namespace>/totem_lumen/spawn_light/<profile>.json
```

```json
{
  "priority": 100,
  "entity_tags": ["totem-lumen:nether_mobs"],
  "block_sensitivity": [0.35, 1.0, 1.0],
  "environment_sensitivity": [0.10, 1.0, 1.0]
}
```

Higher-priority matching profiles override lower-priority profiles. Entity selectors may use exact entity IDs and/or entity-type tags.

### Built-in Nether policy

Totem Lumen ships a `totem-lumen:nether_mobs` entity-type tag and default profile:

| Light component | Red sensitivity | Green | Blue |
| --- | ---: | ---: | ---: |
| Player/block light | 0.35 | 1.0 | 1.0 |
| Dimension environment | 0.10 | 1.0 | 1.0 |

Thus Nether-origin mobs ignore part of red light instead of treating a red lamp identically to white/green/blue illumination. Data packs can replace or supersede this behavior.

## Sky light and sun angle

The server does not ray trace the sun. The gameplay model keeps sky lighting separate from RGB block lighting and retains Minecraft's sky-light/dimension spawn thresholds. Time, weather and sky brightness can change without rebuilding the RGB block-light cache.

The client Vulkan renderer may still use the true sun direction for long shadows, direct illumination, GI and presentation. This intentionally means visual precision can exceed gameplay precision without compromising authoritative behavior.

## Dimension environment rules

Optional resources:

```text
data/<dimension namespace>/totem_lumen/dimension_lighting/<dimension path>.json
```

```json
{
  "environment_color": [0.45, 0.08, 0.02],
  "gameplay_strength": 3,
  "affects_spawning": false
}
```

Dimension environment light is a query-time constant/function, not 8 KiB duplicated into every section. `affects_spawning: false` allows a dimension to look bright without silently changing mob balance.

## Network and disk impact

| Resource | Alpha 32 policy |
| --- | --- |
| RGB voxel field over network | Not synchronized |
| Client lighting-rule snapshot | Join and successful `/reload` only |
| Spawn spectral profiles | Server-only |
| Dimension gameplay profiles | Server-only |
| RGB cache in world save | Not persisted |
| Normal steady-state network traffic | Approximately zero |

The existing client packet remains RGB-color-only for Alpha 31 wire compatibility. `gameplay_strength` is server-only and does not need to be sent to the renderer.

## Overall resource evaluation

| Area | Evaluation |
| --- | --- |
| RAM | Medium; dominated by 8 KiB per allocated lit section |
| Idle CPU | Very low |
| Spawn-query CPU | Very low / O(1) |
| Light-update CPU | Bounded by work and time budgets |
| Large redstone resilience | Queue + coalescing + dirty spawn safety |
| Network | Very low |
| Disk | Zero additional persistent light data in v1 |
| Multiplayer scaling | Mainly affected by number of loaded, actually-lit sections |
| Gameplay consistency | High; server authoritative |
| Vulkan coupling | None on dedicated server |

## Alpha 32 implementation checklist

| Item | Status |
| --- | --- |
| Stable server `gameplay_strength` | Implemented |
| Shared fallback emission colors | Implemented |
| Packed RGB section cache | Implemented |
| Sparse vanilla-emissive source index | Implemented |
| Budgeted chunk source scan | Implemented |
| Budgeted 15-block local relight | Implemented |
| Dirty section safety | Implemented |
| Hostile mob spawn hook | Implemented |
| Data-driven spectral profiles | Implemented |
| Built-in Nether red sensitivity | Implemented |
| Optional dimension environment rule | Implemented |
| Periodic performance counters | Implemented |
| RGB cache persistence | Deliberately deferred |
| Gameplay dynamic moving lights | Future extension |
| Server GI / sun ray tracing | Deliberately excluded |

## Runtime validation plan

1. Dedicated server boots without loading any client/Vulkan classes.
2. Place/remove torch, soul torch, redstone lamp and lava; confirm local RGB field converges.
3. Stress a repeating redstone-lamp bank and verify lighting tick time stays inside the 2 ms budget while backlog grows safely.
4. Confirm hostile mobs cannot spawn in dirty sections during convergence.
5. Compare white/green/blue/red controlled rooms for ordinary monsters.
6. Compare the same rooms with entities in `totem-lumen:nether_mobs`; red should suppress spawning less strongly.
7. Run `/reload` after changing `gameplay_strength`; indexed emitters must update without a whole-world voxel rescan.
8. Verify chunk unload releases RGB arrays/source entries and removes stale neighbor light.
9. Verify Alpha 31-compatible clients still receive the RGB emission-color payload.
