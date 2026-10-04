# Persistent Vulkan Pipeline Cache

Status: **Current disk reliability follow-up below; historical Alpha 38 design retained for context.**

## 2026-10-03: concurrent cache file reliability

The current implementation uses a session-wide cache, unlike the historical
short-lived design described below. This follow-up changes only opaque cache
file I/O; the cache key, Vulkan handle lifetime, shaders and GPU dispatch are
unchanged.

- `PipelineCacheFiles` opens one file handle for both size checking and reading.
  Reads retain a 64 MiB payload limit even if another writer grows the file;
  at most one extra byte is read to detect overflow.
- Each save stages in its own uniquely named file beside the target, writes
  the complete blob, forces its contents to storage, and atomically replaces
  the target. Two game processes no longer share/truncate the same `.tmp` file.
- If atomic replacement is unavailable or fails, saving reports an I/O failure
  instead of attempting non-atomic replacement. The old cache remains usable.
  Cleanup removes only this operation's staging file.
- Unreadable, invalid-sized or driver-rejected data is ignored, not deleted:
  another process may already have replaced the pathname with a valid cache.
  Normal compilation can still proceed with an empty cache.
- Saves are **last successful writer wins**, not a cross-process cache merge.
  An abrupt process exit can leave a unique staging file. This is not a
  directory-fsync/power-loss durability guarantee.

Seven deterministic tests cover missing files, complete replacement, read-size
bounds, invalid writes, unsupported atomic replacement, injected I/O failure,
and overlapping writers with bounded readers. The isolated native compiler
probe now imports/exports through this same helper and records its compiled
class hashes. Neither unit tests nor compiler probes establish in-world
startup speed, visual correctness, FPS, or Apple Silicon/MoltenVK acceptance.

### Validation evidence

- Java 25.0.3 / Gradle 9.6.1: final `--offline build prepareCompileResearch`
  passed (159 tests, zero failures/errors). Shader compilation, configured
  Minecraft 26.3 mixin descriptors and RGB verifiers passed. The separate
  worker shutdown verifier also passed before the final non-file guard update.
  This checkout has no Gradle wrapper; the documented local distribution was used.
- Final RTX 5060 Ti compiler probes:
  `build/reports/compile-research/cache-files-bootstrap-empty-final-20261003/`
  and `cache-files-bootstrap-import-final-20261003/`. Both completed with
  `vkResult=0` and `deviceDestroyed=true`. The first saved 21,980 bytes; the
  second process read the same SHA-256 and reported `applicationCacheHit=true`.
  Native creation times were 31.467 ms and 25.875 ms respectively; these are
  single compiler observations, **not** before/after performance evidence.
  The driver-internal cache was uncontrolled. No world or GPU dispatch ran.
- Dedicated-server entrypoint smoke: Minecraft 26.3 / Loader 0.19.5 / Fabric
  API 0.160.5+26.3 loaded Lumen and logged server gameplay registration, then
  exited at the expected EULA gate. The new empty run directory also logged
  missing `server.properties`; no existing world or EULA acceptance was used.
  Evidence: `/tmp/lumen-cache-server-smoke.kWKZZ1/run/logs/latest.log`.
  This is a load check, not world-tick/gameplay acceptance. `jdeps` confirms
  the new helper depends only on `java.base`; research classes are absent
  from the player JAR.
- Remaining gates: actual Minecraft cache-save/relaunch and rendering lifecycle,
  Apple Silicon/MoltenVK, visual acceptance and FPS. No shared contracts,
  Observer UI/transport, gameplay-light rules or network payloads changed.
  This patch does not close their broader runtime matrices or authorize release.

## Historical Alpha 38 notes

## Motivation

Alpha 37 established that the P14C generic-model renderer is correct on Apple M4 + MoltenVK 1.4.2, but first-use driver pipeline compilation is far too slow for normal startup:

| Pipeline | Alpha 37 observed first compile |
| --- | ---: |
| P12-P15 base | 475,698 ms (~7m 55.7s) |
| P16 reflection | 71,467 ms (~71.5s) |

GLSL -> SPIR-V was only 91 ms in the same run. The startup bottleneck is therefore the driver/MoltenVK/Metal pipeline stage, not shaderc.

Alpha 35/36 already moved pipeline creation off Minecraft's render thread and split P16 from the base pass, so a slow compiler no longer freezes the game. Alpha 38 adds persistence so successful driver work can be reused on later launches.

## Design

Totem Lumen uses Vulkan's opaque `VkPipelineCache` data as the persistence boundary.

```text
pipeline build worker
    -> identify physical device / driver
    -> load persisted opaque cache blob if present
    -> vkCreatePipelineCache(pInitialData)
    -> vkCreateComputePipelines(..., pipelineCache, ...)
    -> vkGetPipelineCacheData
    -> atomic file replace
    -> vkDestroyPipelineCache
```

The `VkPipelineCache` handle is deliberately short-lived. It exists only around one pipeline creation and is destroyed before the build call returns. This avoids introducing a long-lived Vulkan child object whose lifetime would have to be coordinated with Minecraft's VkDevice shutdown.

Base and P16 use the same cache file. After the base pipeline succeeds, its cache data is written. P16 then loads that updated blob, adds any driver data generated by its pipeline, and writes the combined opaque blob back. A later game launch can therefore begin with cache data produced by both pipelines.

## Cache key and location

The cache file lives under Minecraft's game directory:

```text
cache/totem-lumen/vulkan-pipelines/
```

The filename includes:

- Totem Lumen cache schema version;
- Vulkan `vendorID`;
- Vulkan `deviceID`;
- Vulkan `driverVersion`;
- Vulkan `pipelineCacheUUID`.

The Vulkan implementation owns the binary format. Totem Lumen never interprets or mutates the blob.

A driver/GPU/cache-UUID change naturally selects a different file instead of feeding incompatible data to the new implementation.

## Failure policy

Pipeline cache persistence is an optimization, never a renderer correctness dependency.

- missing cache -> `MISS`, compile normally and save on success;
- valid cache -> `HIT`, pass it to pipeline creation and save the updated blob;
- rejected/corrupt cache -> discard it and retry with an empty `VkPipelineCache`;
- cache I/O or cache-object failure -> fall back to `vkCreateComputePipelines(..., VK_NULL_HANDLE, ...)`;
- pipeline creation itself still follows the existing Alpha 35/36 background failure policy.

Cache blobs larger than 64 MiB are rejected as a defensive sanity limit. Writes use a temporary file followed by atomic replacement when the filesystem supports it.

## Runtime logging

Expected first launch on a clean cache:

```text
Vulkan pipeline cache MISS: shader=totem_lumen_p12_one_bounce_gi.comp, ...
...
Vulkan pipeline cache SAVED: shader=totem_lumen_p12_one_bounce_gi.comp, ...

Vulkan pipeline cache HIT: shader=totem_lumen_p16_reflection.comp, ...
...
Vulkan pipeline cache SAVED: shader=totem_lumen_p16_reflection.comp, ...
```

P16 may already report `HIT` on the first launch because the base pipeline creates and saves the shared cache before P16 starts. That does not imply P16 itself was previously compiled; the cache blob may contain only base-pipeline entries at that point.

Expected second launch on the same GPU/driver:

```text
Vulkan pipeline cache HIT: shader=totem_lumen_p12_one_bounce_gi.comp, ...
Vulkan pipeline cache HIT: shader=totem_lumen_p16_reflection.comp, ...
```

The important measurements are still the existing pipeline completion elapsed values. `HIT` means cached opaque driver data was supplied; it does not by itself guarantee a specific speedup because the Vulkan implementation decides what can be reused.

## Validation gate

Alpha 38 is complete only after Apple M4 runtime testing verifies:

1. first clean launch reaches base and P16 READY and logs cache `SAVED`;
2. cache file exists and has a non-zero sane size;
3. second launch logs cache `HIT` for the base pipeline;
4. second-launch base elapsed time is materially lower than Alpha 37's 475,698 ms baseline;
5. second-launch P16 elapsed time is measured against the 71,467 ms baseline;
6. deleting the cache returns behavior to a clean `MISS` without breaking renderer startup;
7. no Vulkan/MoltenVK errors are introduced.

If MoltenVK returns a cache blob but second-launch elapsed time does not improve materially, persistent `VkPipelineCache` alone is insufficient. The next investigation should target MoltenVK/Metal-native shader/pipeline caching behavior rather than adding waits or moving the same work between threads again.
