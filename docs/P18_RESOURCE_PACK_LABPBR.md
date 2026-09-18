# P18 Resource Pack / LabPBR Integration — Alpha 44

## Status

- **P18A renderer-resolved texture identity + LabPBR 1.3 decode contract: IMPLEMENTED / CI pending**
- **P18B bounded resident PBR texture GPU scene: PENDING**
- **P18C textured BRDF + alpha coverage integration (albedo/normal/AO/roughness/F0/metal/emission): PENDING**
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
- G 0–229: dielectric F0 mapped to 0–0.08.
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

## P18B — bounded resident PBR texture scene

Planned constraints:

- do not upload every texture from every enabled resource pack;
- select only sprites referenced by currently resident P5/P14/P14E/P17 scene data;
- immutable CPU texture records survive only as Totem-owned decoded pixels/metadata;
- resource reload replaces texture payload revisions without retaining old Minecraft image objects;
- missing `_n`/`_s` maps use neutral defaults;
- fixed GPU budget with explicit diagnostics and nearest/resident prioritization;
- no player runtime dependency on shader compilers or external PBR tooling.

A compact SSBO-backed resident atlas is preferred for the first Vulkan-compute baseline because the
existing P5/P12/P16 pipelines currently bind one scene storage buffer. A later measured optimization
may migrate PBR maps to sampled Vulkan images if that is materially faster on native Vulkan and
MoltenVK.

## P18C — shared material evaluation

One surface evaluation must feed all consumers:

- primary/camera shading;
- Sun/Moon visibility response;
- local-light response;
- diffuse GI;
- P15 transmission where applicable;
- P16 reflection/Fresnel;
- emissive surface radiance.

The initial LabPBR fields are:

- tangent-space normal;
- AO;
- roughness;
- dielectric F0 / metal identity;
- emission.

Height/POM, porosity wetness and subsurface scattering are intentionally later gates.

### Alpha coverage is part of the baseline

Albedo alpha is not discarded during P18 upload/shading.

- alpha = 0: the texel is a hole; the candidate triangle hit is rejected and ray traversal continues;
- alpha = 255: ordinary opaque textured hit;
- 0 < alpha < 255: fractional coverage/transmission is retained and must not be promoted to P15 glass semantics.

This alpha test occurs after geometric triangle intersection but before the shared hit is accepted, so
primary rays, Sun/Moon shadows, local-light shadows, GI and P16 reflection see the same cutout
silhouette. True glass/water volume/interface transmission remains owned by P15/P14E.

## P18D — fidelity follow-up

After static blocks are correct:

- block-entity texture identity;
- player skin / entity texture sampling;
- armor/equipment;
- emissive entity layers.

This is where the remaining P17D material-fidelity debt moves.

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
