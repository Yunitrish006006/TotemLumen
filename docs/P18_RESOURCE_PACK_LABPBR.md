# P18 Resource Pack / LabPBR Integration — Alpha 44

## Status

- **P18A renderer-resolved texture identity + LabPBR 1.3 decode contract: IMPLEMENTED / CI PASS**
- **P18B bounded PBR texture GPU scene + textured-cube surface table: IMPLEMENTED / CI PASS**
- **P18C shared LabPBR shading + alpha coverage: IMPLEMENTED / SHADER CI PASS; runtime visual validation pending**
- **P18D entity/block-entity material fidelity: PENDING**
- **P18E height/POM and secondary LabPBR channels: DEFERRED**

Alpha 44 starts from the accepted Alpha 43 renderer/settings branch and does not widen the existing
32-bit voxel word unless a later measured gate proves it necessary.

## Target format

The first supported resource-pack material format is **LabPBR 1.3**.

Resource convention:

```text
<texture>.png      albedo / alpha
<texture>_n.png    normal XY + AO + height
<texture>_s.png    smoothness + F0/metal + porosity/SSS + emission
```

A pack may declare `format=lab-pbr/1.3` in `assets/minecraft/optifine/texture.properties`.
P18 must also degrade safely when a pack omits the declaration but supplies valid auxiliary maps.

### Canonical LabPBR 1.3 decode

Normal map:

- R/G: DirectX tangent-space X/Y. Totem converts Y-down into its +Y tangent convention.
- B: ambient occlusion.
- A: height for later POM.

Specular map:

- R: perceptual smoothness, decoded as `roughness = (1 - R)^2`.
- G 0–229: dielectric F0 stored linearly as G/255 (229 ≈ 0.898).
- G 230–237: predefined metals.
- G 238–254: reserved/custom-metal-compatible range.
- G 255: custom metal using albedo as conductor reflectance.
- B 0–64: porosity.
- B 65–255: subsurface/thickness domain.
- A 1–254: emissive strength; 0/255 are treated as non-emissive endpoints.

`LabPbr13Decoder` is the CPU reference implementation. GPU decoding must match its unit tests.

## P18A — renderer-resolved surface identity

P18 cannot infer textures from block registry ids. Minecraft/Fabric model output remains the source
of truth.

For P14C block models, the existing `FabricBlockStateModel.emitQuads(...)` path now retains:

- exact renderer-emitted quad positions;
- resolved texture sprite resource id;
- per-vertex UV coordinates normalized to the sprite, not the stitched atlas.

The sprite is recovered from the quad's declared atlas with Fabric's `SpriteFinder`. Retained data
contains only strings/floats in Totem-owned immutable records; no `TextureAtlasSprite`,
`TextureAtlas`, `QuadView`, or other Minecraft renderer object is kept.

Sprite-local UV is mandatory. Atlas UV would become stale whenever resource reload restitches or
moves a sprite.

### Generic meshes

`BlockModelMeshRegistry` static mesh dedup now includes `QuadSurface` metadata in addition to
positions. Two models with identical geometry but different face textures must not collapse to one
mesh id.

P14D dynamic meshes currently enter through the compatibility path and receive untextured surfaces.
Entity/block-entity texture fidelity remains P18D.

### Canonical full cubes

Full cubes must keep the fast AABB intersection path. P18A therefore records their six renderer-
resolved faces into `BlockSurfaceSetRegistry` using fixed order:

```text
0 -X
1 +X
2 -Y
3 +Y
4 -Z
5 +Z
```

The registry uses a 12-bit-compatible maximum id of 4095. P18A only captures/deduplicates these
surface sets; it intentionally leaves the current `SURFACE_CUBE` geometry code unchanged until
P18B uploads the surface-set table and P18C can consume it without visual regression.

## P18B — bounded PBR texture scene

The implemented first-pass GPU scene uses a compact SSBO-backed texture tail because the existing
P5/P12/P16 pipelines bind one scene storage buffer.

Current invariants:

- model extraction only observes renderer-resolved sprite ids; PNG decode is deferred to the client
  tick and limited per tick so section meshing workers do not synchronously decode resource packs;
- decoded albedo/`_n`/`_s` pixels are copied into Totem-owned immutable data;
- stable 12-bit texture handles survive resource reload while payload revisions are replaced;
- the GPU tail preserves raw albedo alpha plus normal/specular auxiliary channels;
- each uploaded texture is bounded to 128x128 while preserving aspect ratio;
- total GPU texture capacity is bounded to 2,097,152 texels with explicit dropped-texture diagnostics;
- missing `_n`/`_s` maps are represented explicitly and fall back to baseline material behavior;
- canonical full cubes use a fixed indexed six-face surface-set table and remain on the AABB fast path;
- generic P14C mesh records carry sprite-local UV plus texture handle;
- no player runtime dependency on external PBR tooling is introduced.

A later measured optimization may migrate PBR maps to sampled Vulkan images if that is materially
faster on native Vulkan and MoltenVK. The current bounded selection is handle/order based rather than
a final distance-prioritized residency policy; that remains a possible optimization after runtime
profiling.

## P18C — shared material evaluation

One surface evaluation must feed all consumers:

- primary/camera shading;
- Sun/Moon visibility response;
- local-light response;
- diffuse GI;
- P15 transmission where applicable;
- P16 reflection/Fresnel;
- emissive surface radiance.

The implemented shared `P18SurfaceSample` resolves, for accepted P14C/textured-cube hits:

- resource-pack albedo;
- tangent-space normal reconstructed from LabPBR R/G;
- AO from the normal-map B channel;
- perceptual smoothness -> roughness;
- linear dielectric F0 or metal identity;
- predefined metal codes 230–237 and custom-metal-compatible fallback;
- LabPBR emission.

The ray-hit ABI remains unchanged. Textured identity/UV/tangent basis is reconstructed only for
accepted shading hits, avoiding a wider shared `HitResult` contract across P14E/P17/P16.

P12 environment lighting, GI and local lights consume the same material sample. P16 reflection uses
the same normal, roughness, F0, metallic state and albedo. AO affects ambient/indirect terms rather
than directly suppressing emissive radiance.

Height/POM, porosity wetness and subsurface scattering are intentionally later gates.

### Alpha coverage is part of the baseline

Albedo alpha is not discarded during P18 upload/shading.

- alpha = 0: the texel is a hole; the candidate triangle hit is rejected and ray traversal continues;
- alpha = 255: ordinary opaque textured hit;
- 0 < alpha < 255: stochastic coverage; the candidate is accepted with probability alpha/255 using
  a stable per-candidate hash plus frame seed, so temporal accumulation converges toward the original
  coverage without turning the surface into a fake glass volume.

This alpha test occurs after geometric triangle/AABB intersection but before the shared hit is
accepted, so primary rays, Sun/Moon shadows, local-light shadows, GI and P16 reflection see the same
coverage silhouette. True glass/water volume/interface transmission remains owned by P15/P14E.

## Stable material ABI

P18 texture/material tuning is data-driven after ABI v4. The generated production GLSL reads a
fixed 24-word descriptor per texture. Runtime material values use the stable descriptor slots below:

```text
+11 baseline_emission_scale
+12 labpbr_emission_scale
+13 roughness_scale
+14 normal_strength
+15 alpha_cutoff
+16 reflection_scale
+17..+23 reserved
```

These values are uploaded as GPU data. Editing texture rules therefore does **not** change the
generated GLSL source, SPIR-V cache key or MoltenVK pipeline identity.

Built-in defaults live at:

```text
assets/totem-lumen/material_rules.json
```

A resource pack may override that resource. Example:

```json
{
  "textures": {
    "minecraft:block/campfire_log_lit": {
      "baseline_emission_scale": 0.0
    },
    "minecraft:block/example_emissive": {
      "labpbr_emission_scale": 1.5,
      "roughness_scale": 0.8,
      "normal_strength": 1.2,
      "alpha_cutoff": 0.5
    }
  }
}
```

Semantics:

- `baseline_emission_scale`: multiplies coarse Minecraft/BlockState surface self-emission while
  leaving the block's local-light contribution untouched;
- `labpbr_emission_scale`: multiplies the LabPBR specular alpha emissive channel;
- `roughness_scale`: adjusts resolved roughness after baseline/LabPBR decoding;
- `normal_strength`: scales tangent-space normal-map X/Y before Z reconstruction;
- `alpha_cutoff`: `0` keeps stochastic partial-alpha coverage; values above zero use a fixed
  threshold.

Changing only these rules must not require shader or Metal pipeline recompilation. ABI layout
changes still require a one-time shader rebuild and must increment the P18 texture ABI version.

## Stable renderer runtime data

Common renderer tuning is now uploaded through a reserved 96-word frame header and a 16-word
material runtime record. Values live in:

```text
assets/totem-lumen/renderer_runtime.json
assets/totem-lumen/block_material_rules.json
```

The runtime header covers local-light gain, reflection spread/energy/bias, transmission layer and
epsilon controls, water/lava optical colors, fluid reflection properties, surface-emission gain,
and GI display gain. Header words 89 through 95 are reserved for future numeric controls.

The per-material runtime record contains emission RGB/strength, transmission RGB, opacity, IOR,
roughness, metallic, feature flags, local-light radius/intensity scales, reflection scale, and one
reserved word.

Block rules support ordered `exact`, `prefix`, `suffix`, and `contains` matchers. A resource
pack can therefore tune individual blocks or families without changing generated GLSL. Numeric rule
changes require scene/material data refresh only; they do not change the SPIR-V or MoltenVK pipeline
cache identity.

This is the intended invalidation boundary:

```text
material_rules.json / block_material_rules.json / renderer_runtime.json
    -> GPU data changes
    -> shader binary unchanged

ray-tracing algorithm / control flow / ABI layout
    -> shader source changes
    -> one-time SPIR-V / driver pipeline rebuild
```

## Surface emission separation

Block light emission and visible surface self-emission are separate concerns.

The voxel/material table still keeps a block's Minecraft light-emission level so campfires, lamps and other light sources continue to illuminate nearby geometry. P18 surface shading then resolves self-emission per textured surface:

- when a LabPBR specular map exists, its per-texel emissive channel is authoritative and coarse BlockState emission is not added underneath;
- built-in `material_rules.json` assigns `baseline_emission_scale: 0` to vanilla campfire wood sprites; this is data, not a shader special case;
- campfire flame sprites remain eligible for the vanilla block-emission fallback when no LabPBR emissive map is present.

This prevents the entire campfire model from glowing while preserving its local-light contribution and animated flame emission.

## Animated textures

Animated Minecraft textures are part of the P18 texture scene rather than a per-frame CPU upload.

For any observed albedo, normal, or specular resource with a `.png.mcmeta` animation section, Totem Lumen now retains:

- frame width/height;
- default `frametime`;
- explicit `frames` ordering;
- per-frame `time` overrides;
- the `interpolate` request for diagnostics;
- independent timelines for albedo, normal and specular/emission maps.

If a LabPBR `_n` or `_s` map has no sidecar metadata but matches the albedo frame grid, it inherits the albedo animation. This covers packs where auxiliary maps rely on the base texture animation.

GPU residency stores unique frame combinations once plus a bounded per-tick lookup timeline. The current world game tick is sent in scene header word 53, so alpha cutout, albedo, normal, roughness/metal and LabPBR emission all select the same current animation state without re-uploading the whole P18 texture tail every tick.

The current baseline uses discrete frame selection. Minecraft `interpolate: true` is detected and retained but sub-tick blending is deferred; frame order and timing are still preserved.

This path is intended to cover animated block/model surfaces such as campfire/flame-style textures as long as Minecraft's resolved model geometry exposes the sprite to the existing P14/P18 capture path.

## P18D — fidelity follow-up

After static blocks are correct:

- block-entity texture identity;
- player skin / entity texture sampling;
- armor/equipment;
- emissive entity layers.

This is where the remaining P17D material-fidelity debt moves.

## CI gate

Run #420 at head `795c32b2d82ea1211261b0ee6a2d5d505e77af7c` passed:

- Java 25 compile + unit tests;
- P18 texture/surface ABI tests;
- P18 textured-surface verification in full base, P17 and P16;
- P18 LabPBR shading verification in full base, P17 and P16;
- shaderc compile with zero errors for bootstrap, P12/P14E full base, P17 enhanced base and P16
  split reflection.

## Runtime gate

Alpha 44 is not accepted until at least:

1. a LabPBR 1.3 resource pack is detected after resource reload;
2. changing/reloading the pack updates retained PBR textures without world restart;
3. stone/wood-like dielectrics visibly change roughness/F0 without changing geometry;
4. predefined/custom metals produce conductor-like reflection behavior;
5. tangent normal maps perturb lighting/reflection direction without changing hit geometry;
6. AO modulates indirect/ambient contribution without crushing direct emissive light;
7. LabPBR emission contributes on the textured surface;
8. a pack with no PBR maps falls back to the Alpha 43 baseline;
9. full cubes remain on the fast intersection path;
10. generic P14C meshes use their renderer-resolved per-quad texture identity;
11. Alpha 43 fluid/entity/render-settings behavior remains functional;
12. zero-alpha albedo texels produce true ray-visible holes on P14C surfaces;
13. intermediate alpha does not become a fake opaque triangle or a fake P15 glass volume;
14. bootstrap readiness remains independent of the larger PBR-aware pipelines.
