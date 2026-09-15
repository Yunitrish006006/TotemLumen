# P16 MoltenVK pipeline stall

## Runtime finding

Alpha 34 fixes the LWJGL `MemoryStack` overflow during `vkCreateShaderModule`, but Apple Silicon runtime logs show the next blocking stage clearly:

```text
P14B Vulkan pipeline creation START: shader=totem_lumen_p12_one_bounce_gi.comp
```

with no matching `COMPLETE` before the client becomes unresponsive. The P16 monolithic compute shader is approximately 64k GLSL characters and 165k bytes of O0 SPIR-V.

MoltenVK must translate SPIR-V to MSL and compile a Metal pipeline during `vkCreateComputePipelines`. That work must never block Minecraft's render thread.

## Accepted fix direction

1. Keep GLSL -> SPIR-V compilation on the existing background prewarm thread.
2. Use shaderc performance optimization for the production prewarm SPIR-V, while CI still verifies the exact transformed shader.
3. Create the main GI Vulkan compute pipeline on a dedicated background thread once Minecraft's Vulkan device is available.
4. Until the pipeline is ready, keep rendering vanilla Minecraft and retry non-blockingly on later frames.
5. Reuse the prepared pipeline across Totem Lumen render-target/resource recreations instead of recompiling it on window resize/world reload.
6. Add persistent `VkPipelineCache` storage as the follow-up so later launches can bypass repeated SPIR-V -> MSL conversion on MoltenVK.

## Runtime gate

The render thread must never call the expensive main-GI `vkCreateComputePipelines` path. A successful runtime log should show background pipeline `START` and `COMPLETE` messages while Minecraft remains responsive, followed later by `P5 stable GPU lookup READY`.
