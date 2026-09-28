# Totem Lumen 0.1.0-alpha.61

Minecraft 26.3 · Fabric · Java 25

## Player-facing changes

- Minecraft RGB light from placed sources now spreads to nearby surfaces promptly after a block change. Removing a source clears its surrounding light.
- Server-owned gameplay RGB state survives world saves and provides a warm field on re-entry while live chunks reconcile against the current world.
- Minecraft RGB avoids making a torch-lit patch brighter than open sky at clear noon. Torch light remains visible at night and in shade.
- The Smooth Lighting option now softens sky illumination across terrain vertices, as it already did for placed RGB light.
- The client limits background RGB prediction near the player and records queue/timing diagnostics for future performance work.

## Tested scope

- Java 25 `gradle check` includes unit tests, RGB verifiers, mixin descriptor checks and packaged shader compilation.
- A 26.3 Apple M4/MoltenVK development client loaded the copied test world with Minecraft RGB and Indigo vertex sampling active.
- The player reported torch add/remove, repeated world entry, boundary approach, sky-versus-torch scenes and perceived response/performance as visually OK. The player also confirmed the smooth sky-light hard edge was gone after the fix.
- Separate local dedicated-server and saved-field probes confirmed one source remove/add and nearby propagation case; see [runtime evidence](GAMEPLAY_RGB_RUNTIME_2026-09-28.md).

## Alpha limitations

- Visual checks do not establish equal settled RGB values across repeated reloads, opposite chunk load orders or all chunk/section boundaries. Offline emitter removal, rule reload and dedicated-server/client numerical parity remain open.
- Frame time, broad material coverage and the full Totem Vulkan profile still need in-world validation. This version is an Alpha release for Minecraft 26.3.

Requires Fabric Loader 0.19.5 or newer and the matching Minecraft 26.3 Fabric API. The client renderer uses Minecraft's Vulkan graphics backend. A dedicated server does not need Vulkan.
