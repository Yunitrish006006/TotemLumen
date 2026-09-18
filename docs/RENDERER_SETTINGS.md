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
| GI quality | Low / Balanced / High | Balanced | 1 / 2 / 4 one-bounce GI samples per frame |
| Shadow quality | Low / Balanced / High | Balanced | 1 / 2 / 4 Sun/Moon soft-shadow transmission rays |
| Ray distance | 64 / 128 / 256 blocks | 256 | Primary/shared scene trace distance |
| Internal resolution | Low / Balanced / High | Balanced | 120 / 160 / 240 internal pixels wide; height follows window aspect |
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

Changing internal resolution must not cause the large MoltenVK pipelines to be recompiled.

P5 therefore allocates its render/history resources once at the maximum **High** extent (240 pixels wide, aspect-correct height) for the current window aspect. The selected quality controls only the active render extent:

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

Local point lights remain point emitters and therefore keep a single exact visibility/transmission ray per light. The shadow-quality setting controls the area-like Sun/Moon directional sampling path.

## Reflection semantics

The reflection master toggle skips P16 dispatch when disabled.

At two bounces, P16 performs a true second ray from the first reflected surface. It does not re-add the first reflection. The second bounce shares P14E/P17 geometry. If water reflections are disabled, raw exact-water hits are filtered out as reflection interfaces while P15 water transmission remains available to the base renderer.

## Diagnostics

The Diagnostics page retains:

- bootstrap / full P12-P15 / P17 / P16 compile state;
- full Render View cycling;
- one-click GI Composite;
- one-click P14E Fluid Geometry;
- F8 as the quick Render View shortcut.

## Build gate

CI must verify all runtime-setting shader markers and shaderc-compile:

1. bootstrap readiness;
2. P12-P15 + P14E full base;
3. P14E + P17 enhanced base;
4. P14E + P16 + P17 reflection.

Bootstrap remains free of these staged quality features so changing the settings system cannot reintroduce the cold-start readiness regression.
