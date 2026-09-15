# P16 MoltenVK pipeline stall

## Runtime finding

Alpha 34 fixed the LWJGL `MemoryStack` overflow during `vkCreateShaderModule`, but Apple Silicon runtime logs exposed the next blocking stage:

```text
P14B Vulkan pipeline creation START: shader=totem_lumen_p12_one_bounce_gi.comp
```

with no matching `COMPLETE` before the client became unresponsive. The Alpha 34 P16 monolithic compute shader was approximately 64k GLSL characters and 165k bytes of O0 SPIR-V.

MoltenVK must translate SPIR-V to MSL and compile a Metal pipeline during `vkCreateComputePipelines`. That work must never block Minecraft's render thread.

## Alpha 35 result

Alpha 35 moved main-GI pipeline creation to a dedicated daemon worker. Runtime testing on Apple M4 + MoltenVK 1.4.2 confirmed that Minecraft remained responsive, but the background pipeline still did not complete after more than five minutes. The player could enter the world and use debug controls, while Totem Lumen stayed on vanilla rendering because `P5 stable GPU lookup READY` could not occur until the monolithic pipeline existed.

This established that background threading was necessary but insufficient: the monolithic P12-P16 driver compile itself had become impractical on the tested Apple path.

## Alpha 36 accepted resolution

Alpha 36 changes the pipeline boundary rather than extending timeouts:

1. P12-P15 becomes the base compute pipeline and remains the renderer readiness gate.
2. P16 reflection/roughness becomes an independent second compute pass over the same scene SSBO.
3. A compute-to-compute buffer barrier connects the base pixel writes to the reflection pass.
4. P16 shader and driver pipeline compilation run independently on a daemon worker.
5. If P16 compilation is slow or fails, the P12-P15 renderer remains usable; only reflections are unavailable.
6. The P16 monolithic Java shader injection is disabled.
7. Large SPIR-V data remains on native heap memory rather than LWJGL `MemoryStack`.

Alpha 36 CI records the following O0 shader sizes:

| Pass | GLSL chars | SPIR-V bytes |
| --- | ---: | ---: |
| P12-P15 base | 60,796 | 157,952 |
| P16 reflection | 40,898 | 107,128 |

A shaderc performance-optimization experiment for the split reflection pass was rejected: it expanded that shader to 1,515,084 bytes and took about 20 seconds to compile in CI. O0 is therefore the accepted production path for both passes at this milestone.

The detailed synchronization and readiness contract is recorded in `P16_MULTIPASS_SPLIT.md`.

## Persistent pipeline cache follow-up

A device/driver-compatible persistent `VkPipelineCache`, keyed by pipeline-cache compatibility and Totem Lumen shader revision, is still useful to reduce repeated startup compilation.

It is now an optimization only. Alpha 36 correctness and base-renderer availability must not depend on a warm cache or on P16 pipeline readiness.

## Runtime gates

The base renderer should become ready independently:

```text
Background shader prewarm COMPLETE
Background Vulkan pipeline creation START
Background Vulkan pipeline creation COMPLETE
P5 stable GPU lookup READY
```

P16 then has its own optional readiness sequence:

```text
P16 split pipeline creation START
P16 split pipeline creation COMPLETE
P16 multipass reflection READY
```

The critical Alpha 36 invariant is:

> `P5 stable GPU lookup READY` must not wait for `P16 split pipeline creation COMPLETE`.

If the second sequence is slow or fails, Minecraft and the P12-P15 Totem Lumen renderer must remain responsive and visible.
