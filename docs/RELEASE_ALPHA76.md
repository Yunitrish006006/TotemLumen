# Totem Lumen 0.1.0-alpha.76

Minecraft 26.3 · Fabric · Java 25

- Minecraft RGB now preloads nearby blocks that emit light in their current state. Shroomlights, glowstone and lit furnaces can illuminate terrain as it comes into view.
- The preload gives nearby lights and lights along your route priority. Lava has a separate work budget so large pools leave time for other emitters.
- Removing a nearby non-lava light or changing its color now prioritizes recomputing the surrounding terrain, reducing how long its old glow lingers.
