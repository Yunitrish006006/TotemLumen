# Server Lighting World Rules

Totem Lumen Alpha 32 treats emissive color and stable gameplay intensity as server-authoritative world data. Rules are loaded from the world's enabled server data packs. Emissive color is synchronized to compatible Totem Lumen clients; gameplay intensity remains server-side and feeds the authoritative RGB gameplay-light field.

## Rule location

Each block has one JSON resource. The resource namespace and relative path identify the block:

```text
data/<block namespace>/totem_lumen/lighting/<block path>.json
```

Examples:

```text
data/minecraft/totem_lumen/lighting/torch.json
    -> minecraft:torch

data/minecraft/totem_lumen/lighting/soul_torch.json
    -> minecraft:soul_torch

data/examplemod/totem_lumen/lighting/crystal_lamp.json
    -> examplemod:crystal_lamp
```

Minecraft's normal data-pack stack determines which resource wins when several enabled packs provide the same path.

## Format

```json
{
  "emission_color": [1.0, 0.55, 0.22],
  "gameplay_strength": 13
}
```

`emission_color` is normalized RGB. Every component must be a finite value from `0.0` through `1.0`.

`gameplay_strength` is optional and must be an integer from `0` through `15`. When omitted, gameplay lighting uses the block state's vanilla `getLightEmission()` value. This is the stable representative value for flickering visual lights.

A rule still cannot turn a normally non-emissive `BlockState` into a source. This keeps source discovery bounded and compatible with Minecraft's existing emissive-state semantics.

Invalid rules fail data-pack reload instead of partially applying an inconsistent authoritative state.

## Runtime behavior

- Server data resources own the authoritative rule set.
- Joining compatible clients receive the complete RGB color snapshot.
- Successful `/reload` broadcasts the new color snapshot.
- The existing client wire format intentionally remains RGB-only for Alpha 31 compatibility.
- `gameplay_strength` is server-only and does not need to be synchronized to Vulkan rendering.
- The server keeps all vanilla-emissive positions in a sparse source index, including sources currently configured to gameplay strength `0`. This allows `/reload` to re-enable them without scanning every loaded voxel.
- Rule changes queue bounded local relights instead of synchronously rebuilding the loaded world.
- Blocks without an explicit rule use Totem Lumen's shared built-in fallback tint and their vanilla emission level.

See [`SERVER_GAMEPLAY_LIGHTING.md`](SERVER_GAMEPLAY_LIGHTING.md) for storage, propagation, spawning, spectral sensitivity, dimension lighting and performance budgets.
