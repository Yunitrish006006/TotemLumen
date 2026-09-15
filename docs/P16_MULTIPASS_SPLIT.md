# P16 Multi-pass Reflection Split

## Why Alpha 36 exists

Alpha 34 proved that the large P16 SPIR-V blob could not live on LWJGL `MemoryStack`. Alpha 35 moved `vkCreateComputePipelines` off Minecraft's render thread, which kept the game responsive, but Apple M4 + MoltenVK 1.4.2 runtime testing showed the background pipeline creation could remain inside the Metal compile path for more than five minutes without completing.

The important distinction is:

- Alpha 35 solved the render-thread freeze;
- it did not make the monolithic P12-P16 Metal pipeline practically compilable on the tested Apple path.

Waiting longer is therefore not the accepted architecture.

## Alpha 36 pass model

```text
Scene SSBO
   |
   +--> Pass A: P12-P15 base renderer
   |      - primary voxel RT
   |      - P13 environment lighting
   |      - P14 local block geometry
   |      - P15 filtered glass transmission
   |      - temporal / denoise / one-bounce diffuse GI
   |      - writes base RGBA8-packed pixel words
   |
   +--> compute -> compute SSBO barrier
   |
   +--> Pass B: P16 reflection / roughness
          - reconstructs primary ray
          - reuses extracted P14 geometry trace helpers
          - reuses P15 filtered glass trace helpers
          - one bounded secondary reflection ray
          - Schlick Fresnel + metallic F0
          - adds reflected radiance to Pass A pixel words
   |
   +--> existing compute -> transfer barrier
   +--> existing buffer -> image copy
   +--> Minecraft HUD/world composite
```

Both passes bind the same scene storage buffer. Alpha 36 does **not** add a second full scene buffer or change the packed 32-bit voxel ABI.

## Compilation boundaries

The former P16 Java shader transform is now intentionally a no-op for the base shader. Pass A is transformed only through P12-P15.

Pass B is generated from a reduced source. It starts from the proven P12-P15 transformed shader but extracts only the contiguous helper regions required by reflection:

1. section lookup + P14 geometry + trace functions;
2. pack/unpack/material/random helpers;
3. P13 environment + P15 transmission helpers.

It does not carry the base pass temporal accumulation, spatial denoise, diffuse GI integration, debug branches or original `main()` control flow.

This is a shader-complexity split, not just a thread split.

## Runtime readiness and fallback

Pass A remains the renderer readiness gate. Once the base pipeline is ready, Totem Lumen may render P12-P15 frames immediately.

Pass B starts only after a live scene SSBO exists. Its shaderc and Vulkan/Metal pipeline creation run on a dedicated daemon worker. While Pass B is compiling:

- Minecraft remains responsive;
- Totem Lumen base lighting remains active;
- reflection is temporarily absent.

If Pass B fails or a driver compiler stalls, the failure is isolated to reflection. It must not disable the P12-P15 renderer or force the player back to permanent vanilla rendering.

## Synchronization

The P5 command stream originally had:

```text
base dispatch -> compute-to-transfer barrier -> pixel buffer copy
```

Alpha 36 changes it to:

```text
base dispatch
-> compute-to-compute buffer barrier
-> optional P16 dispatch
-> existing compute-to-transfer barrier
-> pixel buffer copy
```

The inserted barrier uses shader-write source access and shader-read/write destination access over the shared scene SSBO. The existing final barrier therefore covers the P16 pixel writes before transfer.

## Runtime log gates

Expected base renderer path:

```text
Background shader prewarm COMPLETE
Background Vulkan pipeline creation START
Background Vulkan pipeline creation COMPLETE
P5 stable GPU lookup READY
```

Reflection starts independently after the scene buffer is attached:

```text
P16 split pipeline creation START
P16 split pipeline creation COMPLETE
P16 multipass reflection READY
```

The key Alpha 36 requirement is that `P5 stable GPU lookup READY` does not depend on `P16 split pipeline creation COMPLETE`.

## CI gates

CI must compile both shader sources independently using their production settings:

- P12-P15 base shader: O0, because the established monolithic base path is already validated there;
- P16 split shader: performance optimization, matching the generic non-main `VulkanComputeProgram.create` path.

A change is not mergeable if either shader fails shaderc verification.

## Follow-up

Persistent `VkPipelineCache` remains useful after Alpha 36, but it is now an optimization rather than the mechanism required to make the renderer usable. Future renderer phases should prefer additional bounded passes over continuously growing one monolithic compute shader.
