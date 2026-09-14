# Totem Lumen Agent Instructions

## Project direction

Totem Lumen is a Minecraft 26.2 Fabric client rendering mod. The renderer is Vulkan-first and Vulkan-only.

### Hard constraints

- Never add an OpenGL renderer, OpenGL compute fallback, or raw OpenGL code.
- Apple Silicon support is mandatory through Minecraft's Vulkan -> MoltenVK -> Metal path.
- Vulkan compute voxel RT is the minimum supported ray-tracing backend.
- Hardware Vulkan RT is optional and must be feature-detected.
- Do not add required player-side mod dependencies beyond Fabric API without explicit approval.
- Do not require players to install Vulkan SDK, MoltenVK, Xcode, shader compilers, RenderDoc, CUDA, DLSS SDK, or other development tooling.
- Prefer packaged/precompiled shader assets for releases.

## Minecraft 26.2 rules

- Target Java 25.
- Minecraft 26.2 is unobfuscated; use the `net.fabricmc.fabric-loom` toolchain and current Mojang names.
- Follow the extraction/render-state direction of the 26.2 rendering pipeline.
- Prefer public Blaze3D abstractions when possible.
- If private Vulkan backend access is required, isolate it behind a minimal bridge/mixin package.

## Architecture

Keep layers separate:

1. Minecraft integration/extraction.
2. Totem Lumen CPU scene.
3. Vulkan GPU scene/resources.
4. Ray tracing backend.
5. Lighting.
6. Temporal accumulation/denoising.
7. Composition.

Do not let later Vulkan code directly depend on mutable `ClientLevel`/`BlockState` collections during command recording.

## Development style

- Implement one roadmap phase at a time and keep each phase independently verifiable.
- Add explicit diagnostics for GPU resources, scene counts, and feature detection.
- Treat Vulkan resource lifetime and synchronization errors as correctness bugs, not warnings.
- Do not optimize before there is a measurable timestamp/counter for the path being optimized.
- Keep Apple Silicon and a native Vulkan desktop GPU in the validation matrix.

Read `docs/ARCHITECTURE.md` and `docs/ROADMAP.md` before renderer changes.
