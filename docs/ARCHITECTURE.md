# Totem Lumen Architecture

## Non-negotiable constraints

1. Totem Lumen's client renderer is Vulkan-only. Do not add an OpenGL implementation or OpenGL fallback.
2. Apple Silicon is a required client target through Minecraft 26.2's Vulkan backend and MoltenVK/Metal translation path.
3. Vulkan compute voxel ray tracing is the compatibility baseline. Hardware RT extensions are optional acceleration only.
4. Player/runtime dependencies stay limited to Fabric Loader, Fabric API, and Totem Lumen unless explicitly approved.
5. Do not require players or server operators to install Vulkan SDK, MoltenVK, shader compilers, Xcode, RenderDoc, CUDA, DLSS SDK, or similar developer tooling.
6. Runtime shaders/assets must be packaged with the mod.
7. Dedicated-server gameplay lighting must not initialize or reference client Vulkan classes.
8. Gameplay-affecting lighting is server authoritative. Client visual results are never trusted for spawning or other world rules.

## Top-level split

```text
                         Minecraft 26.2
                              |
              +---------------+----------------+
              |                                |
              v                                v
       Dedicated/common server               Client
              |                                |
              v                                v
  Server data-pack lighting rules      Fabric extraction/lifecycle
              |                                |
              v                                v
  ServerGameplayLightEngine             SceneUpdateQueue
  |- sparse source index                       |
  |- packed RGB section cache                  v
  |- bounded propagation                    RayScene
  |- sky/environment query state          |- static sections
  `- convergence/metrics                 |- materials
              |                           |- visual lights
              v                           `- dynamic scene
  Spawn/gameplay predicates                    |
                                               v
                                        Vulkan GPU scene
                                        |- voxel buffers
                                        |- material buffers
                                        |- light buffers
                                        `- history images
                                               |
                                               v
                                        RayTracingBackend
                                        |- Vulkan voxel compute (baseline)
                                        `- Vulkan hardware RT (optional)
                                               |
                                               v
                               Lighting -> Temporal -> Denoise -> Composite
```

The server and client may consume the same authoritative emission-color definitions, but they do not share a renderer or a voxel-light cache.

## Server gameplay-lighting boundary

The server light field is intentionally not physically rendered light. It is a deterministic gameplay approximation:

| Property | Server policy |
| --- | --- |
| Channel range | RGB chroma + intensity A, each `0..15` |
| Combination | component-wise maximum |
| Attenuation | at least 1 per block step |
| Maximum propagation | 15 block steps |
| Visual flicker | stable gameplay representative value |
| GI / bounce | none |
| Directional sun shadows | none |
| Storage | sparse 16-bit packed section arrays |
| Recalculation | bounded local rebuilds |
| Backlog safety | dirty sections reject hostile dark-spawn checks |

This subsystem is common/server code only. It must remain runnable on a headless dedicated server.

## Client extraction/rendering boundary

Minecraft mutable world objects must not become long-lived GPU-render-thread inputs. Extraction produces immutable/copy-owned scene updates. Later rendering stages consume Totem Lumen's scene representation instead of reading `ClientLevel` from Vulkan command recording code.

Server authoritative lighting does not change this rule. The client receives compact world-rule snapshots, resolves them during extraction, and submits Totem-owned immutable material/scene data to Vulkan.

## Authoritative data flow

```text
World Data Pack
  |
  +-> block lighting rule
  |     emission_color
  |     gameplay_strength
  |
  +-> spawn spectral profile
  |     block/environment RGB sensitivity
  |
  `-> dimension environment profile
        environment_color
        gameplay_strength
        affects_spawning
              |
              v
        Server authority
              |
       +------+------+
       |             |
       v             v
 gameplay light    color snapshot
 field/spawning    to capable clients
                     |
                     v
                visual renderer
```

Surface appearance remains a client resource-pack/rendering concern. Gameplay-affecting light semantics remain a server/world-data concern.

## Apple Silicon

The Apple client path is the same Vulkan API design used elsewhere. Minecraft/MoltenVK performs translation to Metal. Code must be capability-driven rather than checking `os.name` to select feature implementations.

Apple Silicon-specific rules:

- Do not require `VK_KHR_ray_tracing_pipeline`.
- Do not assume NVIDIA-style subgroup width or vendor intrinsics.
- Keep compute workgroup sizing configurable.
- Prefer incremental dirty-section uploads even with unified memory.
- Track transient/full-resolution render target memory carefully.
- Treat hardware RT as enabled only when the running Vulkan device actually exposes the required features/extensions.

## Server performance rules

- Never relight an entire loaded world because one block changes.
- Never perform renderer-quality ray tracing on the server.
- Chunk emitter discovery must be budgeted.
- Light propagation must have both work and wall-clock ceilings.
- Keep RGB caches derived and non-persistent until profiling proves disk caching is necessary.
- Record timings/counters before changing data structures for optimization.

See [`SERVER_GAMEPLAY_LIGHTING.md`](SERVER_GAMEPLAY_LIGHTING.md) for the complete resource model and accepted planning tables.

## Package direction

```text
dev.totem.lumen
├── gameplay.light      # server-authoritative gameplay lighting
├── world               # server data-pack world rules
├── network             # compact authoritative rule snapshots
├── scene               # immutable/copy-owned CPU scene
├── material            # renderer material model
├── gpu                  # renderer GPU ABI/helpers
└── [client source set]
    ├── integration      # Minecraft client extraction
    ├── render
    ├── platform
    └── vulkan
```
