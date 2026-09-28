# Totem Lumen

Totem Lumen adds configurable RGB gameplay lighting and an experimental Vulkan renderer to Minecraft 26.3.

## Features

- Minecraft RGB profile shows colored light from placed sources on nearby terrain, including smooth sky lighting.
- The server owns gameplay RGB state for world rules and persists source hints across saves.
- The optional Totem Lumen profile explores voxel ray traced lighting, shadows, GI and reflections on Minecraft's Vulkan backend.

## Requirements

- Minecraft Java Edition 26.3
- Fabric Loader 0.19.5 or newer
- Fabric API for Minecraft 26.3
- Java 25

Install the same mod version on the server if you want server owned gameplay RGB rules. The client Vulkan renderer requires Minecraft's Vulkan graphics backend; a dedicated server does not need Vulkan.

This is an alpha. See the [Alpha 61 release notes](https://github.com/Yunitrish006006/TotemLumen/blob/main/docs/RELEASE_ALPHA61.md) for tested behavior and remaining limits. Source code and issues are available on [GitHub](https://github.com/Yunitrish006006/TotemLumen).

Some code and documentation were created with AI assistance and human review.
