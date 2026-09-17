# P13 Overworld Night Sky

## Problem

Alpha 39's P13 Overworld sky had a time-of-day sun disk and night-sky radiance, but no moon primitive or star field. Once Totem Lumen owned the GI Composite sky output, the vanilla moon and stars were therefore absent at night. The same moon omission also meant night surfaces had no directional moon term; they received only the low night-sky ambient contribution.

## Alpha 40 baseline

Alpha 40 keeps the complete Overworld night sky inside the existing P13 environment helpers rather than introducing a separate sky renderer.

- `p13MoonDirection()` is exactly `-p13SunDirection()`, so the moon follows the same captured Overworld clock and is opposite the sun on the celestial path.
- `p13MoonStrength()` fades the moon in only after its direction rises above the horizon.
- `p13MoonDisk()` renders a bounded procedural disk. A small low-intensity halo avoids a one-pixel-looking celestial object at the current low render resolution.
- Moon RGB is intentionally cool and substantially weaker than the sun.
- `p13EnvironmentSurfaceRadiance()` traces moon visibility independently from sun visibility. A blocked moon ray contributes no direct moon term.
- `p13StarRadiance()` adds a deterministic procedural star field to the shared Overworld sky radiance path.
- GI bounce misses continue to call `p13SkyRadiance()`, so both the moon and star field remain part of the environment sampled by the renderer.
- P16's split reflection shader imports the same P13 helper span, so reflections see the same moon, lunar phase and star field without separate implementations.

## Minecraft lunar phase integration

Alpha 40 carries Minecraft 26.2's resolved eight-step lunar phase all the way from extraction into the P13 shader.

- `P13EnvironmentCapture` reads `EnvironmentAttributes.MOON_PHASE` from the client level's resolved dimension environment attributes and stores `MoonPhase.index()`.
- The phase order matches Minecraft's `MoonPhase` enum: full moon, waning gibbous, third quarter, waning crescent, new moon, waxing crescent, first quarter, waxing gibbous.
- The GPU-visible environment word remains 32 bits: 2 dimension bits, 11 time-of-day bits, 3 lunar-phase bits and the original 16-bit stochastic frame seed.
- Reducing time-of-day from 14 to 11 bits gives 2,048 positions around the 24,000-tick day, or about 11.7 ticks per step. This is far below a visually meaningful celestial jump while preserving the full GI seed range.
- `p13MoonPhaseMask()` projects the moon disk onto a procedural sphere and uses a curved terminator to produce crescent, quarter and gibbous silhouettes. New moon suppresses the visible disk; full moon leaves the complete disk visible.
- `p13MoonPhaseBrightness()` uses the eight-step factors `1.0, 0.75, 0.5, 0.25, 0.0, 0.25, 0.5, 0.75` for halo and directional moonlight intensity. The visible lit portion of the disk keeps its local surface brightness rather than being dimmed twice.
- The phase is visual/environment state only. The moon direction remains opposite the sun, matching the existing P13 celestial path rather than attempting a physically simulated Earth-Moon orbit.

## Procedural star field

The Alpha 40 star field is analytic shader math and deliberately does not allocate a texture, storage buffer or extra compute pass.

- `p13StarHash()` generates a stable pseudo-random distribution from a quantized sky cell. It does not use the per-frame GI seed, so holding the camera still never causes random star shimmer.
- `p13StarSkyUv()` rotates the star dome with the captured P13 day phase, so the star pattern moves with the celestial cycle instead of remaining fixed to the world.
- Stars fade in as the sun drops below the horizon and fade out near the horizon to avoid a hard band of bright points at ground level.
- Star size, brightness and warm/cool color temperature vary deterministically per cell. There is no temporal twinkle in Alpha 40 because temporal instability would pollute GI and reflection history.
- Exact world-up sky-ambient sampling is guarded from the procedural star points so stars remain visual celestial sources rather than becoming an accidental fake point light on ordinary upward-facing block surfaces.
- P15 glass transmission already multiplies the shared sky radiance path, so stars seen through clear or stained glass use the existing RGB transmission behavior.
- P16 reflections sample the same `p13SkyRadiance()` function and therefore receive the same moving star field.

Weather-dependent star suppression is intentionally deferred. Alpha 40 derives star visibility from the existing captured time-of-day state and does not yet add another per-frame environment field for rain/cloud coverage or Minecraft's resolved `STAR_BRIGHTNESS` attribute.

## Performance scope

At a given Overworld time, the sun and moon are opposite. The direct-light path therefore normally launches one celestial visibility ray: a sun ray in daytime or a moon ray at night. Near horizon transitions both strengths approach zero, and at new moon the phase brightness is zero, so the existing guards suppress negligible moon rays.

The lunar-phase and star-field changes do not widen the voxel ABI, add a texture, allocate a new Vulkan resource, or create another compute pass. The moon phase mask and star distribution are analytic shader math, while the full 16-bit temporal/GI stochastic seed remains available.

## Runtime validation gate

On the Overworld in `GI Composite`:

1. verify eight consecutive Minecraft lunar phases produce distinct full/gibbous/quarter/crescent/new silhouettes in the expected order;
2. verify full moon uses the existing Alpha 40 disk size and brightness while new moon has no visible bright disk;
3. verify waning and waxing quarters illuminate opposite halves of the disk;
4. compare exposed surfaces at full, quarter, crescent and new moon and confirm directional moonlight decreases with the phase factor and reaches zero at new moon;
5. confirm the moon path remains opposite the daytime sun path and disappears below the horizon during daytime;
6. confirm stars are absent in daylight, fade in through dusk, remain stable while the camera is still, and move gradually with the day/night cycle;
7. confirm the star distribution is sparse and non-grid-like at the current low internal render resolution, with no obvious seam or bright horizon band;
8. confirm roofs/walls and P15 clear/stained glass still occlude/filter direct moonlight correctly, and that the star field is visible through the existing sky transmission path;
9. confirm reflective surfaces pick up the phase-shaped moon and star field through the existing P16 reflection pass;
10. hold the camera still and confirm GI convergence remains stable, demonstrating that the full 16-bit stochastic seed path is preserved and the stars do not introduce temporal shimmer;
11. confirm no shaderc, Vulkan or MoltenVK errors appear and daytime sun behavior remains visually unchanged.

CI validates the packed environment layout, P13 moon/star helper markers and production base/reflection compute shader compilation. In-game silhouette orientation, star density, transport and visual quality remain runtime gates.
