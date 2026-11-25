# Totem Lumen 0.1.0-alpha.74

Minecraft 26.3 · Fabric · Java 25

- Fixed ground fire sometimes leaving nearby surfaces dark when entering newly loaded Nether terrain in the Minecraft RGB profile. Fire age updates could cause the client to skip spreading light from the flame.
- Nearby light scans follow the player while exploring. Changing a nearby flame to soul fire or extinguishing it now takes priority over background light corrections.
- Grouped Nether RGB terrain refreshes so light updates can finish on newly visible terrain.
