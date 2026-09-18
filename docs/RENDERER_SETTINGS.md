# Totem Lumen Renderer Settings

## Status

**Alpha 43 runtime settings UI: IMPLEMENTED / CI PASS; in-world UX/performance validation pending**

Entry point:

```text
Options -> Video Settings -> Totem Lumen...
```

The main page contains user-facing renderer quality and feature controls. Pipeline status and developer Render View controls live on the separate **Diagnostics & Render View** page.

## Defaults and ranges

| Setting | Values | Default | Runtime effect |
| --- | --- | --- | --- |
| Totem Lumen renderer | Off / On | On | Stops/starts Totem Lumen compute submission and final composite; Minecraft Vulkan rendering remains active |
| GI quality | Low / Balanced / High | Balanced | 1 / 2 / 4 one-bounce GI samples per frame |
| Shadow quality | Low / Balanced / High | Balanced | 1 / 2 / 4 Sun/Moon soft-shadow rays and 1 / 2 / 4 samples across each local emissive face |
| Ray distance | 64 / 128 / 256 blocks | 256 | Primary/shared scene trace distance |
| Internal resolution | Low / Balanced / High | Balanced | 50% / 67% / 100% of viewport width; height follows window aspect. High is native through 2560 px wide and capped there above 1440p-class widths. |
| Reflections | Off / On | On | Skips or records the P16 reflection compute pass |
| Water reflections | Off / On | On | Includes/excludes exact P14E water surfaces as reflection interfaces |
| Reflection bounces | 1 / 2 | 1 | Maximum iterative P16 reflection bounces |
| Reflection distance | 16 / 32 / 64 blocks | 64 | Per-bounce P16 trace distance |
| Temporal | Off / Fast / Stable | Stable | No history / 16-sample history / 64-sample history; direct history weight 0 / 0.65 / 0.80 |
| Denoise | Off / Fast / Quality | Fast | Spatial history radius 0 / 1 / 2 (1x1 / 3x3 / 5x5 bounds) |

All values persist in:

```text
config/totem-lumen.properties
```

Legacy configs with `reflectionBounces=0` are interpreted as reflections disabled and migrated in memory to the new master-toggle model.

## Runtime ABI

The existing 64-word scene header has enough reserved room; no voxel ABI widening is required.

| Word | Meaning |
| ---: | --- |
| 7 | primary/shared ray distance as float bits |
| 42 | reflection bounce count (1..2) |
| 43 | reflection distance as float bits |
| 44 | GI samples per frame (1/2/4) |
| 45 | Sun/Moon shadow samples (1/2/4) |
| 46 | reflection master toggle |
| 47 | exact-water reflection toggle |
| 48 | temporal history sample limit (0/16/64) |
| 49 | direct temporal history weight as float bits |
| 50 | spatial denoise radius (0/1/2) |

Changing a runtime setting increments a renderer settings revision. P5 invalidates temporal history on the next frame so quality/range changes cannot blend against incompatible history.

## Internal-resolution ownership

### Active-copy regression fix

The initial fixed-capacity implementation dispatched only the selected active extent but still copied the pixel buffer into the Vulkan image using the maximum-capacity row length and image extent. Because shader output is packed linearly using the active width, low-resolution modes were interpreted with the wrong row stride and only part of the screen appeared rendered.

The copy path now uses the same active dimensions for all three Vulkan copy fields:

```text
bufferRowLength  = active render width
bufferImageHeight = active render height
imageExtent       = active render width x active render height
```

The final GUI composite then samples the corresponding active UV rectangle from the fixed-capacity texture and scales it across the full Minecraft viewport.


Changing internal resolution must not cause the large MoltenVK pipelines to be recompiled.

P5 therefore allocates its render/history resources once at the maximum **High** extent for the current window, capped at 2560 pixels wide with aspect-correct height. The selected quality controls only the active render extent:

- compute dispatch uses the active width/height;
- pixel-buffer barriers/copies cover only the active area;
- the fixed-capacity output texture receives only the active rectangle;
- HUD composition samples only the active UV rectangle and scales it to the screen.

Window aspect changes can still require a safe resource rebuild because the maximum capacity height changes. A quality switch alone does not replace the scene buffer and therefore does not reattach/recompile P12/P17/P16 pipelines.

## Shadow ordering

Runtime Sun/Moon soft-shadow sampling is deliberately layered **after P15 glass transmission**:

```text
P12/P13 lighting
  -> P14 geometry
  -> P13 sky occlusion
  -> P15 colored transmission
  -> runtime shadow-quality sampling
  -> P14E / P17 staged geometry and optics
```

This preserves P15's colored-glass source markers and averages RGB transmission across the selected 1/2/4 directional samples instead of replacing glass with binary visibility.

Local emissive blocks are no longer shaded as center-point emitters. For each receiver, the renderer selects the block face oriented toward that receiver and evaluates 1 / 2 / 4 samples across the face. Each sample has its own receiver cosine, emitter cosine, range attenuation and P15 RGB visibility/transmission ray. The same Shadow Quality setting controls both Sun/Moon soft-shadow sampling and local emissive-face sampling.

## Startup and recompile fallback

Totem Lumen no longer exposes the simplified bootstrap renderer as a player-visible presentation.

- Minecraft's normal world render stays visible while the Vulkan bootstrap and full P12-P18 material-aware base compile.
- The player's currently selected resource pack stays selected; Totem Lumen does not force a resource-pack reload or replace the user's pack.
- The bootstrap program exists only to establish the live Vulkan scene/storage contract needed to compile the full renderer.
- No Totem full-screen composite is drawn until the full base pipeline is ready **and** a complete Totem frame has finished.
- If the full base compile fails, Minecraft's normal renderer remains usable for the rest of the session.
- Pressing **Recompile Renderer Pipelines** immediately returns presentation ownership to Minecraft until the replacement full-base frame is ready.
- P17 dynamic entities and P16 reflections remain optional staged upgrades after the base presentation is already active.

This provides the intended "ordinary Minecraft first, advanced lighting when ready" startup path without an expensive resource reload.

### Compile acceleration

Renderer compilation is split into **preparation** and **scene binding**:

- the tiny Vulkan bootstrap is prepared first so Minecraft remains responsive;
- as soon as the Vulkan device/bootstrap are ready, the full GI / sky / geometry / material pipeline begins compiling even if the player is still in menus;
- player/entity nearest-hit tracing is compiled directly into that same full-lighting pipeline, so MoltenVK does not compile a second near-duplicate 100k+ character base shader;
- after the unified full-lighting pipeline is prepared, the independent reflection pass begins its background prewarm;
- entering a world allocates the scene storage buffer and binds already-prepared pipelines to it instead of starting their expensive driver compilation from scratch.

Two persistent caches are used across launches:

```text
cache/totem-lumen/spirv/v1/<source-hash>.spv
cache/totem-lumen/vulkan-pipelines/<gpu-driver-identity>.bin
```

The SPIR-V cache is keyed by the complete generated GLSL source, target environment and optimization mode, so any shader change invalidates only the affected entry automatically. The Vulkan pipeline cache is keyed by vendor/device/driver/pipeline-cache UUID and is shared by all Totem compute pipelines for the lifetime of the client session.

Logs report SPIR-V HIT/MISS, Vulkan pipeline-cache session HIT/MISS and per-pipeline driver creation time. These timings are the baseline for deciding whether later work should target shader generation or MoltenVK/Metal compilation.

The shared Vulkan pipeline cache does not serialize the whole `vkCreateComputePipelines` call. Independent driver builds may enter pipeline creation concurrently; cache serialization to disk is deferred until all active builds finish.

Apple M4 cold-run measurements showed why the unified base is necessary: the old full-lighting pipeline took about 16m33s in MoltenVK/Metal and the separate near-duplicate entity-enhanced base took another about 20m17s. Warm-cache runs reduced those same driver calls to single-digit milliseconds. The runtime therefore now compiles one unified full-lighting/entity pipeline instead of paying both cold costs.

### Compile progress HUD

While compilation is active, a compact top-left HUD shows the current real pipeline stage and an overall 0–100% staged progress value. The percentage is deliberately stage-based because shaderc/Vulkan/MoltenVK do not expose a trustworthy intra-pipeline percentage callback.

Current staged weights:

- 0–10%: renderer/Vulkan backend discovery and interop;
- 20%: bootstrap pipeline compiling;
- 30%: unified full lighting / materials / player-and-entity ray tracing compiling;
- 85%: unified full lighting is ready and Totem presentation can become active;
- +15% when the independent reflection pipeline finishes;
- 100%: all staged compilation has finished; the HUD disappears automatically.

A manual **Recompile Renderer Pipelines** resets the visible staged progress and the HUD appears again.

## Renderer master toggle

`rendererEnabled=false` is a runtime renderer bypass, not a mod unload:

- P5 does not submit Totem Lumen compute frames;
- the Totem Lumen full-screen composite is skipped, revealing Minecraft's normal Vulkan output;
- compile-progress chat messages are suppressed while disabled;
- existing GPU resources/pipelines remain resident so re-enabling is immediate;
- captured scene data may continue to update in the background so re-enabling does not require a world reload.

The value is persistent and defaults to enabled.

## Reflection semantics

The reflection master toggle skips P16 dispatch when disabled.

At two bounces, P16 performs a true second ray from the first reflected surface. It does not re-add the first reflection. The second bounce shares P14E/P17 geometry. If water reflections are disabled, raw exact-water hits are filtered out as reflection interfaces while P15 water transmission remains available to the base renderer.

## Diagnostics

The Diagnostics page retains:

- Vulkan bootstrap / unified full-lighting-and-entity / reflection compile state;
- full Render View cycling;
- one-click GI Composite;
- one-click P14E Fluid Geometry;
- F8 as the quick Render View shortcut.

## Build gate

CI must verify all runtime-setting shader markers and shaderc-compile:

1. bootstrap readiness;
2. unified full lighting + exact fluids + PBR + dynamic entities;
3. split reflection pass with dynamic-entity tracing.

Bootstrap remains free of these staged quality features and is never composited to the player. It exists only as an internal readiness/compilation bridge, so cold startup cannot replace normal Minecraft presentation with a partial Totem renderer.
