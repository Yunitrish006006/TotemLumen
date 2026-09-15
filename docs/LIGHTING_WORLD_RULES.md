# Server Lighting World Rules

Totem Lumen Alpha 31 can treat emissive light color as a server-authoritative world rule.
The rules are loaded from the world's enabled server data packs and synchronized to every connected
Totem Lumen client that advertises the matching payload channel.

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

Because these are normal server-data resources, Minecraft's data-pack stack decides which file wins
when several enabled packs provide the same resource.

## Format

```json
{
  "emission_color": [1.0, 0.55, 0.22]
}
```

`emission_color` is normalized RGB. Each component must be a finite number from `0.0` through `1.0`.
Invalid rules fail the data-pack reload instead of silently applying a partial rule set.

Alpha 31 intentionally controls color only. Minecraft `BlockState#getLightEmission()` still controls
whether a state emits and its normal 0-15 emission level. A lighting rule therefore does not turn a
normally dark block state into a light source.

## Runtime behavior

- The server loads rules with its `SERVER_DATA` resource manager.
- A joining Totem Lumen client receives the current complete rule snapshot.
- A successful `/reload` sends the new complete snapshot to all connected Totem Lumen clients.
- Clients that do not advertise the Totem Lumen payload are skipped by the server.
- A Totem Lumen client gradually re-extracts already populated sections using the existing
  background section budget, avoiding one large synchronous rebuild after `/reload`.
- Blocks without an explicit world rule keep Totem Lumen's built-in vanilla color fallback.

The Vulkan renderer does not know whether a color came from a world rule or the fallback. Both paths
resolve to the same `MaterialDefinition` emission RGB and existing GPU material ABI.
