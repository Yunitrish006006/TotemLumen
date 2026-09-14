# Totem Lumen Architecture

## Non-negotiable constraints

1. Totem Lumen is Vulkan-only. Do not add an OpenGL implementation or OpenGL fallback.
2. Apple Silicon is a required target through Minecraft 26.2's Vulkan backend and MoltenVK/Metal translation path.
3. Vulkan compute voxel ray tracing is the compatibility baseline. Hardware RT extensions are optional acceleration only.
4. Player runtime dependencies stay limited to Fabric Loader, Fabric API, and Totem Lumen unless explicitly approved.
5. Do not require players to install Vulkan SDK, MoltenVK, shader compilers, Xcode, RenderDoc, CUDA, DLSS SDK, or similar developer tooling.
6. Runtime shaders/assets must be packaged with the mod.

## Layering

```text
Minecraft 26.2
  |
  v
Fabric extraction / lifecycle hooks
  |
  v
SceneUpdateQueue
  |
  v
RayScene
  |- static chunk/section scene
  |- material registry
  |- light registry
  `- dynamic scene
  |
  v
Vulkan GPU scene
  |- voxel buffers
  |- material buffers
  |- light buffers
  `- history images
  |
  v
RayTracingBackend
  |- VulkanVoxelComputeBackend       (required baseline)
  `- VulkanHardwareRtBackend         (optional later)
  |
  v
Lighting -> Temporal -> Denoise -> Composite
```

## Threading boundary

Minecraft mutable world objects must not become long-lived GPU-render-thread inputs. Extraction produces immutable/copy-owned scene updates. Later rendering stages consume Totem Lumen's scene representation instead of reading `ClientLevel` from Vulkan command recording code.

## Apple Silicon

The Apple path is the same Vulkan API design used elsewhere. Minecraft/MoltenVK performs translation to Metal. Code must be capability-driven rather than checking `os.name` to select feature implementations.

Apple Silicon-specific rules:

- Do not require `VK_KHR_ray_tracing_pipeline`.
- Do not assume NVIDIA-style subgroup width or vendor intrinsics.
- Keep compute workgroup sizing configurable.
- Prefer incremental dirty-section uploads even with unified memory.
- Track transient/full-resolution render target memory carefully.
- Treat hardware RT as enabled only when the running Vulkan device actually exposes the required features/extensions.

## Backend policy

P0 performs a public Blaze3D device-info gate only. Direct access to Minecraft's private Vulkan backend is deliberately deferred until there is a concrete P3/P4 requirement. If private backend access becomes necessary, isolate it behind one narrow bridge/mixin package so Minecraft point releases do not leak through the renderer architecture.

## Initial package map

```text
dev.totem.lumen
├── platform
│   └── PlatformProfile
├── render
│   ├── BackendProbe
│   ├── BackendStatus
│   ├── RendererBootstrap
│   └── RendererState
├── scene            # P1+
├── vulkan           # P3+
├── temporal         # P10+
└── backend          # P4+
```
