# P13 Overworld Moon

## Problem

Alpha 39's P13 Overworld sky had a time-of-day sun disk and night-sky radiance, but no moon primitive. Once Totem Lumen owned the GI Composite sky output, the vanilla moon was therefore absent at night. The same omission also meant night surfaces had no directional moon term; they received only the low night-sky ambient contribution.

## Alpha 40 baseline

Alpha 40 adds a procedural moon to the existing P13 environment helpers rather than introducing a separate sky renderer.

- `p13MoonDirection()` is exactly `-p13SunDirection()`, so the moon follows the same captured Overworld clock and is opposite the sun on the celestial path.
- `p13MoonStrength()` fades the moon in only after its direction rises above the horizon.
- `p13MoonDisk()` renders a bounded procedural disk. A small low-intensity halo avoids a one-pixel-looking celestial object at the current low render resolution.
- Moon RGB is intentionally cool and substantially weaker than the sun.
- `p13EnvironmentSurfaceRadiance()` traces moon visibility independently from sun visibility. A blocked moon ray contributes no direct moon term.
- GI bounce misses continue to call `p13SkyRadiance()`, so the moon disk can appear in indirect environment samples.
- P16's split reflection shader imports the same P13 helper span, so reflections see the same moon/environment definition without a separate moon implementation.

## Performance scope

At a given Overworld time, the sun and moon are opposite. The direct-light path therefore normally launches one celestial visibility ray: a sun ray in daytime or a moon ray at night. Near horizon transitions both strengths approach zero, so the guards suppress negligible rays.

The moon change does not widen the voxel ABI, add a texture, allocate a new Vulkan resource, or create another compute pass.

## Known limitation: lunar phases

Alpha 40 deliberately renders a full moon disk whenever the moon is above the horizon. The current GPU-visible environment word stores dimension family, normalized time-of-day phase, and stochastic frame seed; it does not contain the Minecraft day index / 8-step lunar phase.

A later lunar-phase follow-up should explicitly add phase state rather than stealing stochastic seed bits or pretending a brightness-only approximation is equivalent to Minecraft's phase texture.

## Runtime validation gate

On an Overworld clear night in `GI Composite`:

1. the moon must be visible in the sky near midnight;
2. its path must be opposite the daytime sun path;
3. the moon must disappear below the horizon during daytime;
4. exposed surfaces should receive a weak cool directional term at night, visibly much dimmer than daytime sun;
5. roofs/walls should occlude the direct moon term without causing night-sky ambient to leak through sealed spaces;
6. reflective surfaces should be able to pick up the moon/environment through the existing P16 reflection pass;
7. no shaderc, Vulkan or MoltenVK errors should appear;
8. daytime sun brightness and direction should remain unchanged from Alpha 39.

CI validates the P13 moon helper markers and compiles both production compute shaders. In-game visual correctness remains a runtime gate.
