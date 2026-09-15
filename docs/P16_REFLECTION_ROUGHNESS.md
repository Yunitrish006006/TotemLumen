# P16 Reflection / Roughness

Status: **Alpha 33 implementation baseline**

P16 turns Totem Lumen's existing client material roughness/metallic concepts into visible reflection behavior without changing the 32-bit voxel storage ABI or adding a second renderer path.

## Scope

The Alpha 33 baseline adds one deterministic secondary reflection ray for opaque/full-cube surfaces in the GI composite path.

```text
primary hit
  -> decode roughness + metallic
  -> mirror reflection direction
  -> widen direction by roughness
  -> trace one filtered secondary ray
  -> evaluate sky/environment or reflected surface radiance
  -> Schlick Fresnel / metallic F0
  -> add reflection to GI composite
```

This is deliberately a bounded baseline. P16 does **not** add recursive specular transport, multi-bounce reflections, ReSTIR, SSR, a second render pass, or hardware-RT requirements.

## Material fallback model

Until resource-pack/LabPBR metadata is integrated, the client owns a deterministic built-in fallback through `BaselineSurfaceProperties`.

Representative values:

| Surface | Roughness | Metallic |
| --- | ---: | ---: |
| Water | 0.04 | 0.00 |
| Clear/stained glass metadata | 0.05 | 0.00 |
| Ice | 0.12 | 0.00 |
| Gold block | 0.22 | 0.95 |
| Iron block | 0.30 | 0.88 |
| Netherite block | 0.38 | 0.82 |
| Copper block / cut copper | 0.32 | 0.78 |
| Exposed copper | 0.44 | 0.56 |
| Weathered copper | 0.58 | 0.32 |
| Oxidized copper | 0.72 | 0.12 |
| Dirt / ordinary fallback | 0.80 | 0.00 |

These are renderer fallbacks, not server gameplay rules. A future resource-pack PBR layer should replace them client-side.

## Voxel ABI

The low 16 bits of every voxel word remain the material ID and the high 16 bits remain geometry metadata.

P16 adds geometry family:

```text
0x9000 = SURFACE_CUBE

low parameter bits:
0..3  roughness 0..15
4..7  metallic  0..15
```

Both values are quantized to four bits. The family is intentionally treated as a normal full-cube fast path by P14 geometry traversal.

Consequences:

- voxel size remains **32 bits**;
- no new per-voxel GPU buffer is allocated;
- section upload size remains unchanged;
- the existing P14/P15 geometry families remain compatible.

P15 transmissive glass keeps its existing `0x8000` / pane metadata rather than being rewritten as `SURFACE_CUBE`.

## Reflection model

P16 performs one secondary ray from the primary hit point.

- Maximum reflection distance: **64 blocks**.
- Direction starts from ideal mirror `reflect()`.
- Roughness broadens the ray direction using one deterministic sample.
- Roughness spread scales approximately with `roughness²`.
- Fresnel uses the Schlick approximation.
- Dielectric F0 baseline is `0.04`.
- Metallic materials tint F0 toward the material base color.
- Rough surfaces reduce reflected-energy contribution.

The reflected ray uses P15's filtered trace path, so stained/clear glass encountered **along the reflection ray** can tint/attenuate reflected radiance.

## Current limitations

| Item | Alpha 33 status |
| --- | --- |
| One secondary reflection ray | Implemented |
| Roughness-controlled spread | Implemented |
| Metallic F0 response | Implemented |
| Schlick Fresnel | Implemented |
| Reflection through P15 glass along the ray | Implemented |
| Full-cube roughness/metallic metadata | Implemented |
| Shaped-block per-material roughness metadata | Deferred |
| Clear/stained glass surface Fresnel | Deferred with refraction work |
| Recursive/multi-bounce specular | Excluded |
| Texture/PBR-driven surface properties | Future resource-pack/LabPBR phase |
| True texture albedo in RT material response | Future resource-pack integration |

P15 currently allows camera rays to pass through transmissive clear/stained glass before returning the opaque primary hit. P16 therefore does not claim a physically correct glass-surface Fresnel reflection yet. That belongs with the later refraction/interface transport work.

## Performance model

For the current `GI Composite` path, a hit pixel gains at most one additional reflection trace.

```text
P12/P15 GI composite:
  primary + GI + visibility/transmission work

P16 GI composite:
  previous work + <= 1 reflection secondary ray
```

The reflection distance is capped at 64 blocks and uses the existing hashed section lookup / DDA / P14 geometry / P15 transmission path.

No CPU scene-buffer growth is introduced by the P16 surface profile encoding.

Runtime performance must be measured before optimization. In particular, Apple M4 / MoltenVK validation should compare GPU/frame cost before and after P16 in the same scene rather than relying on theoretical ray counts alone.

## Validation plan

1. Shader transform compiles to SPIR-V in CI with the full P12 -> P16 transform chain.
2. Compare gold/iron/netherite blocks with dirt: metals should show substantially stronger reflected radiance.
3. Compare polished/obsidian/ice/water-like low-roughness surfaces with ordinary rough blocks.
4. Move the camera across grazing angles and confirm Schlick Fresnel strengthens reflection toward grazing incidence.
5. Place stained glass between a reflective surface and reflected environment/light and confirm the reflected ray is tinted/attenuated rather than treating glass as opaque.
6. Confirm ordinary rough terrain does not become mirror-like.
7. Confirm live block edits still invalidate the existing temporal/GI history correctly.
8. Confirm no Vulkan validation, shaderc, MoltenVK, or scene-upload errors.
9. Record client frame-time/GPU-cost observations before changing ray count, distance, or sampling strategy.
