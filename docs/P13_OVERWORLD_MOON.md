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
- GI bounce misses continue to call `p13SkyRadiance()`, so the moon can appear in indirect environment samples.
- P16's split reflection shader imports the same P13 helper span, so reflections see the same moon/environment definition without a separate moon implementation.

## Minecraft lunar phase integration

Alpha 40 now carries Minecraft 26.2's resolved eight-step lunar phase all the way from extraction into the P13 shader.

- `P13EnvironmentCapture` reads `EnvironmentAttributes.MOON_PHASE` from the client level's resolved dimension environment attributes and stores `MoonPhase.index()`.
- The phase order matches Minecraft's `MoonPhase` enum: full moon, waning gibbous, third quarter, waning crescent, new moon, waxing crescent, first quarter, waxing gibbous.
- The GPU-visible environment word remains 32 bits: 2 dimension bits, 11 time-of-day bits, 3 lunar-phase bits and the original 16-bit stochastic frame seed.
- Reducing time-of-day from 14 to 11 bits gives 2,048 positions around the 24,000-tick day, or about 11.7 ticks per step. This is far below a visually meaningful celestial jump while preserving the full GI seed range.
- `p13MoonPhaseMask()` projects the moon disk onto a procedural sphere and uses a curved terminator to produce crescent, quarter and gibbous silhouettes. New moon suppresses the visible disk; full moon leaves the complete disk visible.
- `p13MoonPhaseBrightness()` uses the eight-step factors `1.0, 0.75, 0.5, 0.25, 0.0, 0.25, 0.5, 0.75` for halo and directional moonlight intensity. The visible lit portion of the disk keeps its local surface brightness rather than being dimmed twice.
- The phase is visual/environment state only. The moon direction remains opposite the sun, matching the existing P13 celestial path rather than attempting a physically simulated Earth-Moon orbit.

## Performance scope

At a given Overworld time, the sun and moon are opposite. The direct-light path therefore normally launches one celestial visibility ray: a sun ray in daytime or a moon ray at night. Near horizon transitions both strengths approach zero, and at new moon the phase brightness is zero, so the existing guards suppress negligible moon rays.

The lunar-phase change does not widen the voxel ABI, add a texture, allocate a new Vulkan resource, or create another compute pass. The phase mask is analytic shader math and the full 16-bit temporal/GI stochastic seed remains available.

## Runtime validation gate

On the Overworld in `GI Composite`:

1. verify eight consecutive Minecraft lunar phases produce distinct full/gibbous/quarter/crescent/new silhouettes in the expected order;
2. verify full moon uses the existing Alpha 40 disk size and brightness while new moon has no visible bright disk;
3. verify waning and waxing quarters illuminate opposite halves of the disk;
4. compare exposed surfaces at full, quarter, crescent and new moon and confirm directional moonlight decreases with the phase factor and reaches zero at new moon;
5. confirm the moon path remains opposite the daytime sun path and disappears below the horizon during daytime;
6. confirm roofs/walls and P15 clear/stained glass still occlude/filter direct moonlight correctly at non-new phases;
7. confirm reflective surfaces pick up the phase-shaped moon/environment through the existing P16 reflection pass;
8. hold the camera still and confirm GI convergence remains stable, demonstrating that the full 16-bit stochastic seed path was preserved;
9. confirm no shaderc, Vulkan or MoltenVK errors appear and daytime sun behavior remains visually unchanged.

CI validates the packed environment layout, P13 lunar-phase helper markers and production compute shader compilation. In-game silhouette orientation, transport and visual quality remain runtime gates.
