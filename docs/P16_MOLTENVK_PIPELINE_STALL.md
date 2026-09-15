# P16 MoltenVK pipeline stall

## Runtime finding

Alpha 34 fixed the LWJGL `MemoryStack` overflow during `vkCreateShaderModule`, but Apple Silicon runtime logs exposed the next blocking stage:

```text
P14B Vulkan pipeline creation START: shader=totem_lumen_p12_one_bounce_gi.comp
```

with no matching `COMPLETE` before the client became unresponsive. The Alpha 34 P16 monolithic compute shader was approximately 64k GLSL characters and 165k bytes of O0 SPIR-V.

MoltenVK must translate SPIR-V to MSL and compile a Metal pipeline during `vkCreateComputePipelines`. That work must never block Minecraft's render thread.

## Alpha 35 accepted fix

1. Keep GLSL -> SPIR-V compilation on the existing background prewarm thread.
2. Compile production SPIR-V with shaderc performance optimization instead of O0.
3. As soon as Minecraft's Vulkan device is accepted, create the main GI descriptor-set layout, pipeline layout and compute pipeline on a dedicated `TotemLumen-PipelinePrewarm` daemon thread.
4. Until the pipeline is ready, keep rendering vanilla Minecraft and retry non-blockingly on later frames.
5. Reuse the prepared pipeline across Totem Lumen resource/render-target recreations. Per-resource descriptor pools/sets remain cheap and are created only after the shared pipeline is ready.
6. Keep the large SPIR-V allocation on native heap memory; only small Vulkan create-info structures use LWJGL `MemoryStack`.

## Follow-up: persistent pipeline cache

MoltenVK documents Vulkan pipeline-cache serialization as the main way to avoid repeated SPIR-V -> MSL conversion on later launches. A later phase should add a device/driver-compatible persistent `VkPipelineCache` keyed by pipeline-cache UUID and Totem Lumen shader revision.

The cache is an optimization only. Correctness must not depend on it, and an absent/stale cache must fall back to the non-blocking background pipeline prewarm.

## Runtime gate

The Minecraft render thread must never call the expensive main-GI `vkCreateComputePipelines` path.

Expected successful log order:

```text
Background shader prewarm COMPLETE
Background Vulkan pipeline creation START
... Minecraft UI/rendering remains responsive ...
Background Vulkan pipeline creation COMPLETE
P5 stable GPU lookup READY
```

If Metal compilation takes unusually long, the user may temporarily see vanilla rendering, but input/rendering must continue rather than freezing the client.
