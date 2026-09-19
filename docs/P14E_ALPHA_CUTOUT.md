# P14E Alpha-Cutout Ray Silhouettes

## Purpose

P14C copies Minecraft/Fabric block-model quad geometry, but Alpha 40 still treats the entire polygon as opaque. Cross-plane plants therefore block rays through transparent parts of their texture, and leaf cubes can incorrectly use the opaque full-cube fast path.

P14E adds texture-alpha silhouette testing without widening the 32-bit voxel word or binding Minecraft's atlas directly into Totem Lumen's Vulkan descriptors.

## Extraction

For every emitted P14C quad:

1. retain the four sprite-local UV coordinates;
2. resolve the quad's atlas sprite through Fabric's `SpriteFinder`;
3. sample a 32x32 one-bit alpha mask from `SpriteContents`;
4. deduplicate non-opaque masks in the shared model registry;
5. discard all Minecraft atlas/sprite/model references after extraction.

Mask id zero means fully opaque and skips alpha testing.

Vanilla block textures are normally 16x16, so the 32x32 mask preserves their binary cutout silhouette without reducing spatial detail. Higher-resolution resource-pack alpha is intentionally a bounded approximation until the P18 texture/material integration owns full-resolution texture sampling.

Animated sprites use the union of their unique frames. A mask texel remains ray-visible if it is opaque in any animation frame. This avoids geometry flicker caused by the renderer and ray scene advancing animation at different moments.

## GPU layout

The per-voxel ABI remains unchanged:

```text
low 16 bits  = material id
high 16 bits = geometry code / MODEL_MESH id
```

The P14 model scene tail changes from position-only quads to:

```text
quad record:
  12 words  positions (4 x vec3)
   8 words  sprite-local UV (4 x vec2)
   1 word   alpha-mask id
```

Descriptor slot zero is not a real mesh and stores the absolute alpha-mask pool base plus live mask count. The mask pool is packed immediately after the actually used quad records, so P14D animation updates do not copy the unused maximum-quad reservation.

## Ray traversal

P14E runs only after triangle geometry intersection produces barycentric coordinates.

```text
triangle candidate
  -> interpolate sprite-local UV
  -> locate 32x32 mask texel
  -> transparent: reject candidate and continue the ray
  -> opaque: accept normal/distance as before
```

Because P14C's trace helper is shared, the same silhouette applies to:

- primary camera rays;
- directional and local-light shadows;
- P12 diffuse GI;
- P13 environment visibility;
- P15 filtered transmission traversal;
- P16 reflection.

A geometrically canonical six-face cube only uses the `SURFACE_CUBE` fast path when none of its emitted quads has transparent texels. This keeps leaf-like cutout cubes on `MODEL_MESH`.

## Failure policy

Texture/sprite lookup failure is conservative: the affected quad remains opaque and a warning is logged once. Alpha-mask or mesh-capacity exhaustion also uses the existing conservative geometry fallback rather than silently deleting the block.

## Runtime validation gate

Validate in `GI Composite`:

1. flowers/tall grass/crops show their texture silhouette instead of solid rectangular X-planes;
2. rays can see the background through transparent plant pixels;
3. plant shadows follow the cutout silhouette instead of the full quad rectangle;
4. leaves no longer behave as a completely opaque cube where the texture is transparent;
5. GI/environment occlusion does not treat transparent cutout pixels as solid;
6. reflective surfaces see the same cutout silhouette through the P16 pass;
7. ordinary opaque cubes and P14C non-cube models remain unchanged;
8. P14D animated block entities remain stable;
9. log contains `P14E alpha-cutout geometry active` and reports `alphaMasks > 0` after cutout models are extracted;
10. no shaderc, Vulkan or MoltenVK errors appear.
