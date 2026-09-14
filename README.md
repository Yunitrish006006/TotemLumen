# Totem Lumen

Totem Lumen is a client-side Minecraft 26.2 Fabric rendering mod focused on Vulkan-first real-time lighting and voxel ray tracing.

## Project goals

- Vulkan-only rendering path. No OpenGL implementation or fallback inside Totem Lumen.
- Vulkan compute voxel ray tracing is the cross-platform baseline.
- Apple Silicon is a first-class target through Minecraft 26.2's Vulkan backend and MoltenVK/Metal path.
- Vulkan hardware ray tracing is optional acceleration, never a required baseline.
- Player runtime dependencies should remain limited to Fabric Loader, Fabric API, and Totem Lumen.
- Shader binaries and other runtime assets must ship inside the mod; players should not need the Vulkan SDK, MoltenVK, Xcode, RenderDoc, or shader compilers.

## Current milestone

`P0 - Vulkan-only project bootstrap`

The first implementation establishes the Fabric 26.2 build, client lifecycle, platform detection, and a runtime Vulkan backend gate. World extraction and GPU scene construction come next.

## Runtime requirements

- Minecraft Java Edition 26.2
- Fabric Loader 0.19.5 or newer
- Fabric API for Minecraft 26.2
- Java 25 (normally provided by the Minecraft launcher)
- A Minecraft-compatible Vulkan backend

On Apple Silicon, Totem Lumen uses the Vulkan backend exposed by Minecraft. Minecraft handles the MoltenVK-to-Metal translation; users should not install MoltenVK separately.

## Development

The project targets Java 25 and the Minecraft 26.2 unobfuscated Fabric toolchain.

```text
Minecraft 26.2
  -> Fabric extraction/lifecycle
  -> Totem Lumen RayScene
  -> Vulkan GPU scene
  -> Vulkan compute voxel RT
  -> lighting / temporal / denoise / GI
  -> composition
```

Development client runs are configured to request the Vulkan backend.

Until the Gradle wrapper binary is generated in-repository, use Gradle 9.5.1 locally:

```bash
gradle build
gradle runClient
```

CI installs Gradle 9.5.1 explicitly, so the repository build does not depend on a globally configured CI Gradle version.

## Architecture rules

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and [`docs/ROADMAP.md`](docs/ROADMAP.md).

## License

Apache-2.0, matching the existing Totem series repositories.
