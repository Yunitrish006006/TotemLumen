# Totem Lumen 0.1.0-alpha.70

Minecraft 26.3 · Fabric · Java 25

- Minecraft RGB now lights the area around fire, soul fire and magma blocks earlier when Nether chunks load. Lava lighting continues in batches while these smaller sources are processed.
- Dense lava pools now skip redundant propagation from cells surrounded on all six sides by lava of the same color and intensity. Exposed cells and section edges still propagate light.
