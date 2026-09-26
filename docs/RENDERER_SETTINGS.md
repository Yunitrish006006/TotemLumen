# Totem Lumen Renderer Settings

## Status

**Alpha 59 render-profile Video Settings integration: IMPLEMENTED / CI PASS; in-world UX validation pending**

Entry point:

```text
Options -> Video Settings -> Quality & Performance
```

The native **Quality & Performance** section contains a persistent render-profile selector:

```text
Render profile: Minecraft / Totem Lumen
```

Minecraft profile keeps the vanilla world-render quality controls. Totem Lumen profile removes only the vanilla controls whose world-render effect is replaced by Totem and shows Totem GI/shadow/ray-distance/internal-resolution/reflection/temporal/denoise controls in the same section. Shared display/interface and scene-availability settings remain visible in both profiles.

The vanilla brightness/gamma slider is kept as Minecraft's own `Options.gamma()` instance and is displayed in the native Quality & Performance section alongside these controls; Totem Lumen does not create a second brightness setting.

Pipeline status and developer Render View controls remain on **Diagnostics & Render View**.

## Defaults and ranges

| Setting | Values | Default | Runtime effect |
| --- | --- | --- | --- |
| Render profile | Minecraft / Totem Lumen | Totem Lumen | Selects which world-renderer owns presentation and which quality controls are shown |
| GI quality | Low / Balanced / High | Balanced | 1 / 2 / 3 one-bounce GI samples per frame |
| Shadow quality | Low / Balanced / High | Balanced | 1 / 2 / 4 Sun/Moon soft-shadow rays and 1 / 2 / 4 samples across each local emissive face |
| Ray distance | 32 / 64 / 96 / 128 blocks | 64 | Primary/shared scene trace distance |
| Internal resolution | Low / Balanced / High | Balanced | 50% / 67% / 100% scale until the preset pixel ceiling is reached; LOW ≈512x288 budget, BALANCED ≈768x432, HIGH ≈1280x720 |
| Reflections | Off / On | On | Enables the independent P16 reflection compute pass when it is ready |
| Water reflections | Off / On | On | Includes/excludes exact P14E water surfaces as reflection interfaces |
| Reflection bounces | 1 / 2 | 1 | Maximum iterative P16 reflection bounces |
| Reflection distance | 16 / 32 / 64 blocks | 64 | Per-bounce P16 trace distance |
| Temporal | Off / Fast / Stable | Stable | No history / 16-sample history / 64-sample history; direct history weight 0 / 0.65 / 0.80 |
| Denoise | Off / Fast / Quality | Fast | Spatial history radius 0 / 1 / 2 (1x1 / 3x3 / 5x5 bounds) |

All values persist in:

```text
config/totem-lumen.properties
```

The profile slider has three values: pure Minecraft rendering, Minecraft geometry with a
client-computed RGB terrain light field, and Totem Lumen. The server still owns gameplay light
queries and synchronizes world light-source rules, but does not stream the visual RGB field.
Legacy configs without `renderProfile`
migrate from the old `rendererEnabled` boolean automatically. The previous Alpha 59
`renderProfile=MINECRAFT` value migrates to `MINECRAFT_RGB`; legacy `reflectionBounces=0` remains
interpreted as reflections disabled.

In `MINECRAFT_RGB`, loaded block sources propagate three 0–15 channels on the client. Terrain
quads keep Minecraft/Indigo geometry, texture and alpha; their RGB vertex colors carry block and
sky illumination, and a neutral full-bright lightmap lookup prevents Minecraft's scalar block
light from whitening that terrain. Source removal recomputes neighboring contributions under a
tick budget, then publishes the completed local region without exposing the intermediate clear.
Only changed mesh sections and the boundary neighbors needed for vertex interpolation are marked
dirty. Because sky RGB is baked into terrain
vertices, time/sky-epoch changes queue a bounded refresh of loaded sections known to receive sky
light, nearest first; they do not invalidate all compiled geometry at once. Ordinary torches use
a slightly less-red warm-white fallback, while soul and redstone torches keep their own palettes.
Lit redstone ore and deepslate redstone ore use the redstone-torch red for their emitted light;
unlit ore remains non-emissive.
Server and client propagation store light chroma in RGB and attenuation in A within the existing
16-bit field. The A channel is light intensity, not texture transparency, so a warm torch does not
leave a pure-red fringe at its dim edge. This also changes the RGB field
used by server gameplay-light queries. Entity, particle, fluid and other non-terrain render
families have not yet been migrated to this RGB terrain path; the profile is not a complete
replacement of Minecraft's lighting pipeline.

On Indigo terrain, smooth lighting interpolates effective RGB at each quad vertex in floating
point from neighboring light voxels. It does not round the surface average back into four-bit
RGBA, which had produced conspicuous colored squares. With smooth lighting disabled, the quad
uses one unblended block/face value. The texture's original vertex alpha is preserved.

## Server-approved held light

When the server runs Totem Lumen, it resolves luminous BlockItems in either player hand (plus lava
buckets) with the same emission colors and world rules as placed blocks. A bounded equipment
snapshot preserves main and offhand separately; ordinary entity tracking supplies interpolated
positions. Each source is offset toward its actual hand according to body yaw and dominant arm,
using the same world-space position in first and third person. This is
visual lighting only and does not alter vanilla block-light levels or spawning rules. It clears
on unequip, dimension change, disconnect, or entity disappearance.

`MINECRAFT_PURE` and `MINECRAFT_RGB` share depth reconstruction but use separate surface-light
blend states: Pure is additive, while RGB samples a copy of the pre-held-light scene and adds only
the moving RGB contribution above the already-present placed RGB light. Both change
visible terrain brightness without modifying chunk meshes, their texture alpha, or Minecraft's
light engine. `TOTEM_LUMEN` uploads the same moving sources to four reserved light
records in the Vulkan scene and shades them directly, without repacking static sections on motion.
At most four nearby hand lights are presented per client frame; the packet is capped at sixteen.
The resolved models for luminous block items use full-bright lightmap coordinates as well, so a
held or dropped torch/lantern surface remains visible like the corresponding placed emitter
instead of only illuminating nearby geometry. Non-luminous items keep their supplied lightmap.

Minecraft RGB estimates a surface's reflectance from its pre-held-light colour, the source-cell
sky/ambient baseline, and the client RGB field at the visible surface. Its moving light scales
with that reflectance and remaining colour headroom:
dark materials respond less, while light materials in a dark room still brighten without the
previous near-field clipping. The emitter tint is blended toward neutral and handheld strength
is 0.90. A single scene copy is reused for all held sources in the frame. Four bounded 32³
source-relative windows of the client RGB field are packed into one 512×256 atlas, uploaded only
when a light crosses a block, a section inside its window changes, or the dimension/device changes.
Unrelated field revisions are rejected by local section-snapshot identity checks.
The fragment pass compares held versus placed RGB per channel and adds only the positive
difference; this prevents a held torch beside a placed torch from being added at full strength.
Placed RGB is trilinearly sampled near the visible surface so moving the hand across voxel
boundaries does not produce hard square subtraction edges; the handheld sky/ambient baseline
also interpolates neighbouring sky cells as its source moves.
RGB handheld illumination uses a 1.20 centre gain and a steeper polynomial distance curve:
the centre is brighter while the middle and outer radius fade more quickly. The placed-light
subtraction, surface-reflectance weighting, and Minecraft Pure profile are unchanged.
The source-cell baseline is an approximation near cave entrances and other sharp sky-light boundaries.
Held light applies the shared `1.20` radial distance scale before
falloff, matching the placed/server attenuation basis while remaining a depth-based moving-light
approximation rather than occlusion-aware voxel propagation; movement never triggers terrain
remeshing.
Minecraft RGB brightens its client-only visual source field by one light level (capped at 15),
without changing server gameplay-light or spawn decisions. Its handheld pass uses a 90% radius
and a stronger center contribution than the placed-source approximation; Pure is unchanged.
Placed RGB light uses an isotropic client visual distance with a 1.20 falloff scale, plus one
additional visual level at selected five-level intervals. Totem Lumen's held-light path applies
the same radial scale before its physically shaped falloff. The source cell remains unchanged,
and immediate updates, region rebuilds, cross-section edges, and held-light distance attenuation
share the same radial basis.

## Runtime ABI

The current 96-word scene header carries runtime quality/state fields without widening the per-voxel record.

| Word | Meaning |
| ---: | --- |
| 7 | primary/shared ray distance as float bits |
| 42 | reflection bounce count (1..2) |
| 43 | reflection distance as float bits |
| 44 | GI samples per frame (1/2/3) |
| 45 | Sun/Moon shadow samples (1/2/4) |
| 46 | effective reflection-pass-active flag (user enabled and P16 ready) |
| 47 | exact-water reflection toggle |
| 48 | temporal history sample limit (0/16/64) |
| 49 | direct temporal history weight as float bits |
| 50 | spatial denoise radius (0/1/2) |

Changing a runtime setting increments a renderer settings revision. P5 invalidates temporal history on the next frame so quality/range changes cannot blend against incompatible history.

## Internal-resolution ownership

Alpha 53 changed internal resolution from an unbounded viewport percentage into a percentage plus per-preset pixel budget. This prevents fullscreen desktop resolution from multiplying ray-tracing work by several times.

Current budgets:

- LOW: 50% scale, capped near a 512x288-equivalent pixel count;
- BALANCED: 67% scale, capped near 768x432;
- HIGH: native scale, capped near 1280x720.

For viewports below the cap, the configured percentage is used normally. Above it, width is reduced by the square root of the pixel-budget ratio while preserving the viewport aspect ratio.

Alpha 51 also removed the old HUD-overlay presentation model. Render/history resources now match the active internal extent. Totem compute output is presented directly into Minecraft's main render target, and vanilla level drawing is cancelled only after a complete Totem frame is ready.

Consequences:

- changing fullscreen/window size can change the active internal extent without scaling RT work without bound;
- the final screen image is an upscale of the bounded Totem output when the framebuffer exceeds the preset budget;
- F1 affects HUD/GUI only and does not reveal a second vanilla world beneath Totem;
- internal-resolution changes still rebuild size-dependent output/history resources, but do not require shader source changes.

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

- Minecraft's normal world render stays visible while the Vulkan bootstrap and full P12-P18 material-aware base compile or whenever the Minecraft render profile is selected.
- The player's currently selected resource pack stays selected; Totem Lumen does not force a resource-pack reload or replace the user's pack.
- The bootstrap program exists only to establish the live Vulkan scene/storage contract needed to compile the full renderer.
- Totem does not cancel vanilla level drawing until the full base pipeline is ready **and** a complete Totem frame has finished.
- If the full base compile fails, Minecraft's normal renderer remains usable for the rest of the session.
- Pressing **Recompile Renderer Pipelines** returns presentation ownership to Minecraft until the replacement full-base frame is ready.
- P17 dynamic-entity tracing is part of the unified production base; only P16 reflections remain an independent optional staged pipeline.

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

## Render profile

Alpha 58 replaces the user-facing renderer on/off toggle with a persistent render profile:

- `Minecraft`: Totem compute/presentation is bypassed and Minecraft owns normal level drawing;
- `Totem Lumen`: Totem submits compute frames and takes over level drawing after a complete Totem frame is ready.

Alpha 59 places this selector directly in the native **Quality & Performance** section. Switching profile reconstructs the Video Settings screen so the visible controls match the active renderer instead of leaving stale disabled rows.

When Totem Lumen is selected, vanilla controls that only affect the replaced world renderer are filtered from the page. Render distance, simulation distance, entity distance, fullscreen/resolution, FPS/VSync and interface settings remain available because they still affect scene availability or non-world presentation.

The profile is persistent. The old `rendererEnabled` property is retained only for backward compatibility/migration.

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
