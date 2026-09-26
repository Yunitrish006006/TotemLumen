package dev.totem.lumen.integration;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.gameplay.light.PackedRgbLight;
import dev.totem.lumen.gameplay.light.RgbLightAttenuation;
import dev.totem.lumen.render.RendererSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Client-side prediction of the gameplay RGB field.
 *
 * <p>This intentionally mirrors the server's source scan, 0..15 component-max propagation and
 * block dampening. It only reads already-loaded client chunks and is bounded per client tick.
 * The server keeps the same engine for gameplay, but does not stream this visual field to the
 * client; vanilla block/chunk updates are the client input for the local calculation.</p>
 */
public final class ClientGameplayLightPredictor {
    private static final int CHUNK_RADIUS = 2;
    private static final int WORK_BUDGET = 24_000;
    private static final long TIME_BUDGET_NANOS = 2_000_000L;
    private static final Predicate<BlockState> EMITS_LIGHT = state -> state.getLightEmission() > 0;

    private static ClientLevel activeLevel;
    private static String activeDimension;
    private static final Map<ChunkKey, ScanTask> scans = new HashMap<>();
    private static final ArrayDeque<ScanTask> pendingScans = new ArrayDeque<>();
    private static final Map<BlockKey, Character> sources = new HashMap<>();
    /** Avoid a whole-world source scan for every bounded RGB correction. */
    private static final Map<SectionKey, Set<BlockKey>> sourcesBySection = new HashMap<>();
    private static final ArrayDeque<ImmediatePropagationTask> immediatePropagations = new ArrayDeque<>();
    private static final ArrayDeque<RebuildTask> pendingRebuilds = new ArrayDeque<>();
    private static final Set<SectionKey> queuedRebuilds = new HashSet<>();
    private static final Set<SectionKey> knownSourceSections = new HashSet<>();
    private static final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    private static int centerChunkX;
    private static int centerChunkZ;
    private static boolean centerKnown;
    private static boolean changedThisTick;
    private static int loggedSourceEvents;
    private static int lastSkyRefreshKey = Integer.MIN_VALUE;
    private static long lastSkyClock = Long.MIN_VALUE;

    private ClientGameplayLightPredictor() {
    }

    public static void clear() {
        activeLevel = null;
        activeDimension = null;
        scans.clear();
        pendingScans.clear();
        sources.clear();
        sourcesBySection.clear();
        immediatePropagations.clear();
        pendingRebuilds.clear();
        queuedRebuilds.clear();
        knownSourceSections.clear();
        centerKnown = false;
        lastSkyRefreshKey = Integer.MIN_VALUE;
        lastSkyClock = Long.MIN_VALUE;
        ClientGameplayLightField.clearLocal();
        ClientGameplayLightField.clearSkyAffected();
    }

    public static void onChunkLoaded(ClientLevel level, LevelChunk chunk) {
        activate(level);
        enqueueScan(level, chunk);
    }

    public static void onChunkUnloaded(ClientLevel level, LevelChunk chunk) {
        if (activeLevel != level) {
            return;
        }
        ChunkKey key = new ChunkKey(chunk.getPos().x(), chunk.getPos().z());
        ScanTask scan = scans.remove(key);
        if (scan != null) {
            scan.cancelled = true;
        }
        List<RemovedSource> removed = removeSourcesInChunk(key);
        immediatePropagations.removeIf(task -> Math.floorDiv(task.source.x, 16) == key.x
                && Math.floorDiv(task.source.z, 16) == key.z);
        ClientGameplayLightField.clearLocalChunk(activeDimension, key.x, key.z);
        for (RemovedSource source : removed) {
            rescheduleRebuild(
                    SectionKey.fromBlock(source.position.x, source.position.y, source.position.z),
                    Region.aroundBlock(level, new BlockPos(
                            source.position.x, source.position.y, source.position.z
                    ))
            );
        }
    }

    public static void onBlockChanged(ClientLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!level.isInsideBuildHeight(pos.getY())) {
            return;
        }
        activate(level);
        BlockKey key = new BlockKey(pos.getX(), pos.getY(), pos.getZ());
        SectionKey section = SectionKey.fromBlock(pos.getX(), pos.getY(), pos.getZ());
        // The source scan is intentionally incremental. During that window a torch can be
        // removed before it has entered `sources`; derive the old value from the actual client
        // block state as well, otherwise the stale propagated field is never scheduled for a
        // rebuild and the light remains visible.
        char oldSource = sources.getOrDefault(key, sourceFor(oldState));
        char nextSource = sourceFor(newState);
        if (nextSource == 0) {
            removeSource(key);
        } else {
            putSource(key, nextSource);
        }
        boolean sourceChanged = oldSource != nextSource;
        boolean propagationChanged = oldState.getLightDampening() != newState.getLightDampening();
        if (sourceChanged && loggedSourceEvents++ < 16) {
            TotemLumenClient.LOGGER.info(
                    "RGBA source changed: pos={}, oldA={}, newA={}, newHue=({},{},{})",
                    pos, PackedRgbLight.alpha(oldSource), PackedRgbLight.alpha(nextSource),
                    PackedRgbLight.hueRed(nextSource), PackedRgbLight.hueGreen(nextSource),
                    PackedRgbLight.hueBlue(nextSource)
            );
        }
        if (sourceChanged || propagationChanged) {
            // A queued scan or rebuild may have captured the old block before this update.
            // Restart overlapping work with the current source map and block state.
            refreshOverlappingRebuilds(pos);
        }
        if (sourceChanged) {
            if (oldSource == 0 && nextSource != 0) {
                // Additions follow the vanilla fast path: publish the source and propagate from
                // it immediately. The bounded correction task verifies the settled result.
                ClientGameplayLightField.setLocalPacked(
                        activeDimension, pos.getX(), pos.getY(), pos.getZ(), nextSource
                );
                ClientGameplayLightField.finishLocalBatch(true);
                ClientGameplayLightField.requestImmediateUpload();
                immediatePropagations.addLast(new ImmediatePropagationTask(
                        new BlockKey(pos.getX(), pos.getY(), pos.getZ()), nextSource
                ));
            } else {
                immediatePropagations.removeIf(task -> task.source.equals(key));
                // Keep the last complete field visible until the replacement is ready.
                // Clearing the entire influence radius here made other nearby lights blink.
                rescheduleRebuild(section, Region.aroundBlock(level, pos));
                ClientGameplayLightField.requestImmediateUpload();
            }
        } else if (propagationChanged) {
            rescheduleRebuild(section, Region.aroundBlock(level, pos));
        }
    }

    public static void requestRebuild() {
        if (activeLevel == null) {
            return;
        }
        ClientGameplayLightField.clearLocal();
        pendingRebuilds.clear();
        queuedRebuilds.clear();
        for (BlockKey source : sources.keySet()) {
            scheduleRebuild(SectionKey.fromBlock(source.x, source.y, source.z));
        }
    }

    /** Re-extract already-built terrain when switching between pure and RGB presentation. */
    public static void onProfileChanged(ClientLevel level, LocalPlayer player) {
        activate(level);
        queueLoadedTerrainRefresh(level, player);
    }

    /** Refresh all actually loaded terrain, nearest first, when baked RGB sky lighting changes. */
    private static int queueLoadedTerrainRefresh(ClientLevel level, LocalPlayer player) {
        if (player == null) {
            return 0;
        }
        String dimension = level.dimension().identifier().toString();
        int playerChunkX = Math.floorDiv(player.blockPosition().getX(), 16);
        int playerChunkZ = Math.floorDiv(player.blockPosition().getZ(), 16);
        int viewRadius = Math.min(32, Math.max(2, Minecraft.getInstance().options.renderDistance().get() + 1));
        int queued = 0;
        for (int radius = 0; radius <= viewRadius; radius++) {
            for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                for (int offsetX = -radius; offsetX <= radius; offsetX++) {
                    if (Math.max(Math.abs(offsetX), Math.abs(offsetZ)) != radius) {
                        continue;
                    }
                    int chunkX = playerChunkX + offsetX;
                    int chunkZ = playerChunkZ + offsetZ;
                    LevelChunk chunk = level.getChunkSource().getChunk(
                            chunkX, chunkZ, ChunkStatus.FULL, false
                    );
                    if (chunk == null) {
                        continue;
                    }
                    LevelChunkSection[] sections = chunk.getSections();
                    for (int index = 0; index < sections.length; index++) {
                        if (!sections[index].hasOnlyAir()) {
                            ClientGameplayLightField.markSectionDirty(
                                    dimension, chunkX, chunk.getSectionYFromSectionIndex(index), chunkZ
                            );
                            queued++;
                        }
                    }
                }
            }
        }
        return queued;
    }

    private static int queueSkyAffectedTerrainRefresh(ClientLevel level, LocalPlayer player) {
        if (player == null) {
            return 0;
        }
        String dimension = level.dimension().identifier().toString();
        int playerChunkX = Math.floorDiv(player.blockPosition().getX(), 16);
        int playerChunkZ = Math.floorDiv(player.blockPosition().getZ(), 16);
        int viewRadius = Math.min(32, Math.max(2, Minecraft.getInstance().options.renderDistance().get() + 1));
        List<ClientGameplayLightField.SectionCoordinate> affected = new ArrayList<>(
                ClientGameplayLightField.skyAffectedSections(dimension)
        );
        affected.sort(Comparator.comparingInt(section ->
                Math.max(Math.abs(section.x() - playerChunkX), Math.abs(section.z() - playerChunkZ))
        ));
        int queued = 0;
        for (ClientGameplayLightField.SectionCoordinate section : affected) {
            if (Math.max(Math.abs(section.x() - playerChunkX), Math.abs(section.z() - playerChunkZ))
                    > viewRadius) {
                continue;
            }
            if (level.getChunkSource().getChunk(section.x(), section.z(), ChunkStatus.FULL, false) != null) {
                ClientGameplayLightField.markSkySectionDirty(
                        dimension, section.x(), section.y(), section.z()
                );
                queued++;
            }
        }
        return queued;
    }

    public static void tick(ClientLevel level, LocalPlayer player) {
        activate(level);
        if (RendererSettings.renderProfile() == RendererSettings.RenderProfile.MINECRAFT_RGB) {
            int skyKey = VanillaRgbLighting.skyRefreshKey(level);
            long clock = level.getOverworldClockTime();
            long elapsed = lastSkyClock == Long.MIN_VALUE ? 0L
                    : Math.floorMod(clock - lastSkyClock, 24_000L);
            boolean clockJumped = lastSkyClock != Long.MIN_VALUE
                    && Math.min(elapsed, 24_000L - elapsed) > 40L;
            if (lastSkyRefreshKey != Integer.MIN_VALUE
                    && (skyKey != lastSkyRefreshKey || clockJumped)) {
                int queued = queueSkyAffectedTerrainRefresh(level, player);
                TotemLumenClient.LOGGER.info(
                        "RGB sky epoch changed: previous={}, current={}, clockJump={}, terrainSectionsQueued={}",
                        lastSkyRefreshKey, skyKey, clockJumped, queued
                );
            }
            lastSkyRefreshKey = skyKey;
            lastSkyClock = clock;
        } else {
            lastSkyRefreshKey = Integer.MIN_VALUE;
            lastSkyClock = Long.MIN_VALUE;
        }
        if (player != null) {
            int nextChunkX = Math.floorDiv(player.blockPosition().getX(), 16);
            int nextChunkZ = Math.floorDiv(player.blockPosition().getZ(), 16);
            if (!centerKnown || Math.abs(nextChunkX - centerChunkX) > 1 || Math.abs(nextChunkZ - centerChunkZ) > 1) {
                centerChunkX = nextChunkX;
                centerChunkZ = nextChunkZ;
                centerKnown = true;
                enqueueVisibleChunks(level);
            }
        }

        long start = System.nanoTime();
        int remaining = WORK_BUDGET;
        changedThisTick = false;
        while (remaining > 0 && System.nanoTime() - start < TIME_BUDGET_NANOS) {
            int used;
            ImmediatePropagationTask immediate = immediatePropagations.peekFirst();
            if (immediate != null) {
                used = immediate.process(Math.min(remaining, 4096));
                if (immediate.complete) {
                    immediatePropagations.removeFirst();
                }
            } else if (!pendingRebuilds.isEmpty()) {
                RebuildTask rebuild = pendingRebuilds.peekFirst();
                used = rebuild.process(Math.min(remaining, 4096));
                if (rebuild.complete()) {
                    rebuild.publish();
                    pendingRebuilds.removeFirst();
                    queuedRebuilds.remove(rebuild.anchor);
                }
            } else if (nextScan() != null) {
                ScanTask scan = nextScan();
                used = scan.process(Math.min(remaining, 4096));
                if (scan.complete) {
                    finishScan(scan);
                }
            } else {
                break;
            }
            if (used <= 0) {
                break;
            }
            remaining -= used;
        }
        ClientGameplayLightField.finishLocalBatch(changedThisTick);
    }

    private static void activate(ClientLevel level) {
        String dimension = level.dimension().identifier().toString();
        if (activeLevel == level && dimension.equals(activeDimension)) {
            return;
        }
        clear();
        activeLevel = level;
        activeDimension = dimension;
    }

    private static void enqueueVisibleChunks(ClientLevel level) {
        for (int chunkZ = centerChunkZ - CHUNK_RADIUS; chunkZ <= centerChunkZ + CHUNK_RADIUS; chunkZ++) {
            for (int chunkX = centerChunkX - CHUNK_RADIUS; chunkX <= centerChunkX + CHUNK_RADIUS; chunkX++) {
                LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk != null) {
                    enqueueScan(level, chunk);
                }
            }
        }
    }

    private static void enqueueScan(ClientLevel level, LevelChunk chunk) {
        ChunkKey key = new ChunkKey(chunk.getPos().x(), chunk.getPos().z());
        ScanTask old = scans.remove(key);
        if (old != null) {
            old.cancelled = true;
        }
        // A rescan must not remove still-present emitters before the scan finishes. Doing so
        // temporarily drops their RGB contribution and makes lights flash as chunks are revisited.
        ScanTask scan = new ScanTask(level, chunk);
        scans.put(key, scan);
        pendingScans.addLast(scan);
    }

    private static ScanTask nextScan() {
        while (!pendingScans.isEmpty()) {
            ScanTask scan = pendingScans.peekFirst();
            if (scan.cancelled) {
                pendingScans.removeFirst();
                continue;
            }
            return scan;
        }
        return null;
    }

    private static void finishScan(ScanTask scan) {
        pendingScans.removeFirstOccurrence(scan);
        scans.remove(scan.chunkKey, scan);
        Map<BlockKey, Character> observed = new HashMap<>();
        for (BlockKey source : scan.discoveredSources) {
            mutablePos.set(source.x, source.y, source.z);
            char current = sourceFor(activeLevel.getBlockState(mutablePos));
            if (current != 0) {
                observed.put(source, current);
            }
        }
        Iterator<Map.Entry<BlockKey, Character>> iterator = sources.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockKey, Character> entry = iterator.next();
            BlockKey source = entry.getKey();
            if (Math.floorDiv(source.x, 16) == scan.chunkKey.x
                    && Math.floorDiv(source.z, 16) == scan.chunkKey.z
                    && !observed.containsKey(source)) {
                // A block update can add a torch after the scanner has passed its voxel.
                // Recheck live state before treating its absence from the snapshot as removal.
                mutablePos.set(source.x, source.y, source.z);
                char live = sourceFor(activeLevel.getBlockState(mutablePos));
                if (live != 0) {
                    observed.put(source, live);
                    continue;
                }
                iterator.remove();
                unindexSource(source);
                rescheduleRebuild(
                        SectionKey.fromBlock(source.x, source.y, source.z),
                        Region.aroundBlock(activeLevel, new BlockPos(source.x, source.y, source.z))
                );
            }
        }
        for (Map.Entry<BlockKey, Character> entry : observed.entrySet()) {
            BlockKey source = entry.getKey();
            char current = entry.getValue();
            Character previous = putSource(source, current);
            if (previous == null || previous != current) {
                knownSourceSections.add(SectionKey.fromBlock(source.x, source.y, source.z));
                rescheduleRebuild(
                        SectionKey.fromBlock(source.x, source.y, source.z),
                        Region.aroundBlock(activeLevel, new BlockPos(source.x, source.y, source.z))
                );
            }
        }
    }

    private static List<RemovedSource> removeSourcesInChunk(ChunkKey chunk) {
        List<RemovedSource> affected = new ArrayList<>();
        Iterator<Map.Entry<BlockKey, Character>> iterator = sources.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockKey, Character> entry = iterator.next();
            BlockKey key = entry.getKey();
            if (Math.floorDiv(key.x, 16) == chunk.x && Math.floorDiv(key.z, 16) == chunk.z) {
                affected.add(new RemovedSource(key));
                iterator.remove();
                unindexSource(key);
            }
        }
        return affected;
    }

    private static void scheduleRebuild(SectionKey anchor) {
        if (activeLevel == null || queuedRebuilds.add(anchor)) {
            if (activeLevel != null) {
                pendingRebuilds.addLast(new RebuildTask(anchor, Region.around(activeLevel, anchor)));
            }
        }
    }

    private static Character putSource(BlockKey key, char packed) {
        Character previous = sources.put(key, packed);
        sourcesBySection.computeIfAbsent(
                SectionKey.fromBlock(key.x, key.y, key.z), ignored -> new HashSet<>()
        ).add(key);
        return previous;
    }

    private static Character removeSource(BlockKey key) {
        Character previous = sources.remove(key);
        if (previous != null) {
            unindexSource(key);
        }
        return previous;
    }

    private static void unindexSource(BlockKey key) {
        SectionKey section = SectionKey.fromBlock(key.x, key.y, key.z);
        Set<BlockKey> entries = sourcesBySection.get(section);
        if (entries != null && entries.remove(key) && entries.isEmpty()) {
            sourcesBySection.remove(section);
        }
    }

    private static List<RebuildTask.Source> sourcesIn(Region region) {
        List<RebuildTask.Source> result = new ArrayList<>();
        for (int sectionY = Math.floorDiv(region.minY, 16); sectionY <= Math.floorDiv(region.maxY, 16); sectionY++) {
            for (int sectionZ = Math.floorDiv(region.minZ, 16); sectionZ <= Math.floorDiv(region.maxZ, 16); sectionZ++) {
                for (int sectionX = Math.floorDiv(region.minX, 16); sectionX <= Math.floorDiv(region.maxX, 16); sectionX++) {
                    Set<BlockKey> entries = sourcesBySection.get(new SectionKey(sectionX, sectionY, sectionZ));
                    if (entries == null) continue;
                    for (BlockKey key : entries) {
                        if (region.contains(key.x, key.y, key.z)) {
                            Character packed = sources.get(key);
                            if (packed != null) result.add(new RebuildTask.Source(key, packed));
                        }
                    }
                }
            }
        }
        return result;
    }

    /** Deterministic build-time guard for negative-section indexing and source removal. */
    static void verifySourceIndex() {
        if (!sources.isEmpty() || !sourcesBySection.isEmpty()) {
            throw new IllegalStateException("RGB source index verifier requires an unused predictor");
        }
        BlockKey left = new BlockKey(-17, 64, 3);
        BlockKey right = new BlockKey(-16, 64, 3);
        BlockKey distant = new BlockKey(160, 64, 3);
        Region nearby = new Region(-20, 60, 0, -15, 70, 7);
        try {
            putSource(left, (char) 0xF321);
            putSource(right, (char) 0xF456);
            putSource(distant, (char) 0xF789);
            if (sourcesIn(nearby).size() != 2) {
                throw new IllegalStateException("RGB index must return only sources inside the region");
            }
            putSource(right, (char) 0xFABC);
            if (sourcesIn(nearby).stream().noneMatch(source -> source.position.equals(right)
                    && source.packed == (char) 0xFABC)) {
                throw new IllegalStateException("RGB index must reflect source color changes");
            }
            removeSource(left);
            if (sourcesIn(nearby).size() != 1 || sourcesBySection.containsKey(
                    SectionKey.fromBlock(left.x, left.y, left.z))) {
                throw new IllegalStateException("RGB index must remove obsolete source sections");
            }
        } finally {
            sources.clear();
            sourcesBySection.clear();
        }
    }

    private static void refreshOverlappingRebuilds(BlockPos changed) {
        Map<SectionKey, Region> stale = new HashMap<>();
        pendingRebuilds.removeIf(task -> {
            if (!task.region.contains(changed.getX(), changed.getY(), changed.getZ())) {
                return false;
            }
            stale.put(task.anchor, task.region);
            queuedRebuilds.remove(task.anchor);
            return true;
        });
        stale.forEach(ClientGameplayLightPredictor::scheduleRebuild);
    }

    private static void rescheduleRebuild(SectionKey anchor, Region region) {
        if (activeLevel == null) {
            return;
        }
        Region[] combined = {region};
        pendingRebuilds.removeIf(task -> {
            if (!task.anchor.equals(anchor)) {
                return false;
            }
            combined[0] = combined[0].union(task.region);
            return true;
        });
        queuedRebuilds.remove(anchor);
        if (queuedRebuilds.add(anchor)) {
            pendingRebuilds.addFirst(new RebuildTask(anchor, combined[0]));
        }
    }

    private static void scheduleRebuild(SectionKey anchor, Region region) {
        if (activeLevel != null && queuedRebuilds.add(anchor)) {
            pendingRebuilds.addLast(new RebuildTask(anchor, region));
        }
    }

    private static char sourceFor(BlockState state) {
        return ClientRgbVisualLightSource.packedFor(state);
    }

    private static int getPacked(int x, int y, int z) {
        return ClientGameplayLightField.localPackedAt(activeDimension, x, y, z);
    }

    private static int getLocalPacked(int x, int y, int z) {
        return ClientGameplayLightField.localPackedAt(activeDimension, x, y, z);
    }

    private static boolean setPacked(int x, int y, int z, int packed) {
        boolean changed = ClientGameplayLightField.setLocalPacked(activeDimension, x, y, z, packed);
        changedThisTick |= changed;
        return changed;
    }

    private static boolean loaded(int x, int z) {
        return activeLevel != null && activeLevel.hasChunkAt(x, z);
    }

    private record ChunkKey(int x, int z) {
    }

    private record BlockKey(int x, int y, int z) {
    }

    private record PropagationNode(
            BlockKey position,
            int packed,
            float distance,
            float occlusionPenalty,
            BlockKey origin,
            boolean rounded
    ) {
    }

    private record RemovedSource(BlockKey position) {
    }

    private record SectionKey(int x, int y, int z) {
        static SectionKey fromBlock(int x, int y, int z) {
            return new SectionKey(Math.floorDiv(x, 16), Math.floorDiv(y, 16), Math.floorDiv(z, 16));
        }
    }

    private record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        Region union(Region other) {
            return new Region(
                    Math.min(minX, other.minX), Math.min(minY, other.minY),
                    Math.min(minZ, other.minZ), Math.max(maxX, other.maxX),
                    Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ)
            );
        }

        static Region aroundBlock(ClientLevel level, BlockPos pos) {
            int radius = PackedRgbLight.MAX_CHANNEL;
            return new Region(
                    pos.getX() - radius,
                    Math.max(level.getMinY(), pos.getY() - radius),
                    pos.getZ() - radius,
                    pos.getX() + radius,
                    Math.min(level.getMaxY() - 1, pos.getY() + radius),
                    pos.getZ() + radius
            );
        }

        static Region around(ClientLevel level, SectionKey anchor) {
            int minY = Math.max(level.getMinY(), anchor.y * 16 - PackedRgbLight.MAX_CHANNEL);
            int maxY = Math.min(level.getMaxY() - 1, anchor.y * 16 + 15 + PackedRgbLight.MAX_CHANNEL);
            return new Region(
                    anchor.x * 16 - PackedRgbLight.MAX_CHANNEL,
                    minY,
                    anchor.z * 16 - PackedRgbLight.MAX_CHANNEL,
                    anchor.x * 16 + 15 + PackedRgbLight.MAX_CHANNEL,
                    maxY,
                    anchor.z * 16 + 15 + PackedRgbLight.MAX_CHANNEL
            );
        }

        int sizeX() { return maxX - minX + 1; }
        int sizeY() { return maxY - minY + 1; }
        int sizeZ() { return maxZ - minZ + 1; }
        long volume() { return (long) sizeX() * sizeY() * sizeZ(); }
        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    private static final class ScanTask {
        private final ClientLevel level;
        private final LevelChunk chunk;
        private final ChunkKey chunkKey;
        private final LevelChunkSection[] sections;
        private final List<BlockKey> discoveredSources = new ArrayList<>();
        private int sectionIndex;
        private int voxelIndex;
        private boolean cancelled;
        private boolean complete;

        private ScanTask(ClientLevel level, LevelChunk chunk) {
            this.level = level;
            this.chunk = chunk;
            this.chunkKey = new ChunkKey(chunk.getPos().x(), chunk.getPos().z());
            this.sections = chunk.getSections();
        }

        private int process(int budget) {
            int consumed = 0;
            while (sectionIndex < sections.length && consumed < budget) {
                LevelChunkSection section = sections[sectionIndex];
                // The section palette can reject non-emissive sections without visiting all
                // 4096 voxels. Most newly loaded terrain sections contain no RGB sources.
                if (section.hasOnlyAir() || !section.maybeHas(EMITS_LIGHT)) {
                    sectionIndex++;
                    voxelIndex = 0;
                    consumed++;
                    continue;
                }
                int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
                while (voxelIndex < 4096 && consumed < budget) {
                    int localX = voxelIndex & 15;
                    int localZ = (voxelIndex >>> 4) & 15;
                    int localY = (voxelIndex >>> 8) & 15;
                    BlockState state = section.getBlockState(localX, localY, localZ);
                    if (state.getLightEmission() > 0) {
                        int x = chunkKey.x * 16 + localX;
                        int y = sectionY * 16 + localY;
                        int z = chunkKey.z * 16 + localZ;
                        char packed = sourceFor(state);
                        if (packed != 0) {
                            BlockKey source = new BlockKey(x, y, z);
                            discoveredSources.add(source);
                        }
                    }
                    voxelIndex++;
                    consumed++;
                }
                if (voxelIndex >= 4096) {
                    sectionIndex++;
                    voxelIndex = 0;
                }
            }
            if (sectionIndex >= sections.length) {
                complete = true;
            }
            return consumed;
        }
    }

    private static final class RebuildTask {
        private static final int SOURCES = 0;
        private static final int BOUNDARY = 1;
        private static final int PROPAGATE = 2;
        private static final int DONE = 3;

        private final SectionKey anchor;
        private final Region region;
        private final List<Source> sourceSnapshot;
        private final ArrayDeque<PropagationNode> queue = new ArrayDeque<>();
        private final Map<ClientGameplayLightField.SectionCoordinate, char[]> staged = new HashMap<>();
        private int phase = SOURCES;
        private int sourceCursor;
        private long boundaryCursor;

        private RebuildTask(SectionKey anchor, Region region) {
            this.anchor = anchor;
            this.region = region;
            this.sourceSnapshot = sourcesIn(region);
        }

        private int process(int budget) {
            int consumed = 0;
            while (consumed < budget && phase != DONE) {
                if (phase == SOURCES) {
                    while (sourceCursor < sourceSnapshot.size() && consumed < budget) {
                        Source source = sourceSnapshot.get(sourceCursor++);
                        Character currentSource = sources.get(source.position);
                        if (currentSource == null || currentSource != source.packed) {
                            consumed++;
                            continue;
                        }
                        if (loaded(source.position.x, source.position.z)
                                && setMax(source.position.x, source.position.y, source.position.z, source.packed)) {
                            queue.add(new PropagationNode(
                                    source.position, source.packed, 0.0f, 0.0f, source.position, true
                            ));
                        }
                        consumed++;
                    }
                    if (sourceCursor >= sourceSnapshot.size()) phase = BOUNDARY;
                } else if (phase == BOUNDARY) {
                    long count = boundaryCount();
                    while (boundaryCursor < count && consumed < budget) {
                        seedBoundary(boundaryCursor++);
                        consumed++;
                    }
                    if (boundaryCursor >= count) phase = PROPAGATE;
                } else {
                    while (!queue.isEmpty() && consumed < budget) {
                        PropagationNode current = queue.removeFirst();
                        if (current.packed != 0) {
                            if (current.rounded) {
                                for (RgbLightAttenuation.Step step : RgbLightAttenuation.ROUND_STEPS) {
                                    propagate(current, step);
                                }
                            } else {
                                propagateCardinal(current, -1, 0, 0);
                                propagateCardinal(current, 1, 0, 0);
                                propagateCardinal(current, 0, -1, 0);
                                propagateCardinal(current, 0, 1, 0);
                                propagateCardinal(current, 0, 0, -1);
                                propagateCardinal(current, 0, 0, 1);
                            }
                        }
                        consumed++;
                    }
                    if (queue.isEmpty()) phase = DONE;
                }
            }
            return consumed;
        }

        private int stagedAt(int x, int y, int z) {
            char[] values = staged.get(new ClientGameplayLightField.SectionCoordinate(
                    activeDimension, Math.floorDiv(x, 16), Math.floorDiv(y, 16), Math.floorDiv(z, 16)
            ));
            return values == null ? 0 : values[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
        }

        private boolean setStaged(int x, int y, int z, int packed) {
            int previous = stagedAt(x, y, z);
            if (previous == packed) return false;
            ClientGameplayLightField.SectionCoordinate key = new ClientGameplayLightField.SectionCoordinate(
                    activeDimension, Math.floorDiv(x, 16), Math.floorDiv(y, 16), Math.floorDiv(z, 16)
            );
            char[] values = staged.computeIfAbsent(key, unused -> new char[4096]);
            values[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] = (char) packed;
            return true;
        }

        private void publish() {
            ClientGameplayLightField.replaceLocalRegion(activeDimension,
                    region.minX, region.minY, region.minZ,
                    region.maxX, region.maxY, region.maxZ, staged);
        }

        private long boundaryCount() {
            return 2L * region.sizeY() * region.sizeZ()
                    + 2L * region.sizeX() * region.sizeZ()
                    + 2L * region.sizeX() * region.sizeY();
        }

        private void seedBoundary(long cursor) {
            long segment = (long) region.sizeY() * region.sizeZ();
            if (cursor < segment * 2) {
                boolean max = cursor >= segment;
                long index = cursor % segment;
                int y = region.minY + (int) (index / region.sizeZ());
                int z = region.minZ + (int) (index % region.sizeZ());
                int x = max ? region.maxX : region.minX;
                seedFromOutside(x, y, z, max ? x + 1 : x - 1, y, z);
                return;
            }
            cursor -= segment * 2;
            segment = (long) region.sizeX() * region.sizeZ();
            if (cursor < segment * 2) {
                boolean max = cursor >= segment;
                long index = cursor % segment;
                int x = region.minX + (int) (index % region.sizeX());
                int z = region.minZ + (int) (index / region.sizeX());
                int y = max ? region.maxY : region.minY;
                seedFromOutside(x, y, z, x, max ? y + 1 : y - 1, z);
                return;
            }
            cursor -= segment * 2;
            segment = (long) region.sizeX() * region.sizeY();
            boolean max = cursor >= segment;
            long index = cursor % segment;
            int x = region.minX + (int) (index % region.sizeX());
            int y = region.minY + (int) (index / region.sizeX());
            int z = max ? region.maxZ : region.minZ;
            seedFromOutside(x, y, z, x, y, max ? z + 1 : z - 1);
        }

        private void seedFromOutside(int x, int y, int z, int outsideX, int outsideY, int outsideZ) {
            if (!activeLevel.isInsideBuildHeight(y) || !activeLevel.isInsideBuildHeight(outsideY)
                    || !loaded(x, z) || !loaded(outsideX, outsideZ)) {
                return;
            }
            int outside = getPacked(outsideX, outsideY, outsideZ);
            if (outside == 0) return;
            mutablePos.set(x, y, z);
            int incoming = ClientRgbVisualLightSource.attenuate(outside, activeLevel.getBlockState(mutablePos));
            if (incoming != 0 && setMax(x, y, z, incoming)) {
                queue.add(new PropagationNode(
                        new BlockKey(x, y, z), incoming, 0.0f, 0.0f, null, false
                ));
            }
        }

        private void propagateCardinal(PropagationNode from, int dx, int dy, int dz) {
            propagate(from, new RgbLightAttenuation.Step(dx, dy, dz, 1.0f));
        }

        private void propagate(PropagationNode from, RgbLightAttenuation.Step step) {
            int x = from.position.x + step.x();
            int y = from.position.y + step.y();
            int z = from.position.z + step.z();
            if (!region.contains(x, y, z) || !activeLevel.isInsideBuildHeight(y) || !loaded(x, z)) return;
            if (step.diagonal() && !diagonalPathOpen(from.position, step)) return;
            mutablePos.set(x, y, z);
            BlockState destination = activeLevel.getBlockState(mutablePos);
            float nextPenalty = from.occlusionPenalty + RgbLightAttenuation.obstaclePenalty(destination);
            float nextDistance = from.rounded
                    ? Math.max(from.distance, visualDistance(from.origin, x, y, z) + nextPenalty)
                    : 0.0f;
            int candidate = from.rounded
                    ? ClientRgbVisualLightSource.attenuateRounded(from.packed, from.distance, nextDistance)
                    : ClientRgbVisualLightSource.attenuate(from.packed, destination);
            if (candidate != 0 && setMax(x, y, z, candidate)) {
                queue.add(new PropagationNode(
                        new BlockKey(x, y, z), candidate,
                        nextDistance,
                        nextPenalty,
                        from.origin,
                        from.rounded
                ));
            }
        }

        private boolean diagonalPathOpen(BlockKey from, RgbLightAttenuation.Step step) {
            if (step.x() != 0) {
                mutablePos.set(from.x + step.x(), from.y, from.z);
                if (activeLevel.getBlockState(mutablePos).getLightDampening() > 1) return false;
            }
            if (step.y() != 0) {
                mutablePos.set(from.x, from.y + step.y(), from.z);
                if (activeLevel.getBlockState(mutablePos).getLightDampening() > 1) return false;
            }
            if (step.z() != 0) {
                mutablePos.set(from.x, from.y, from.z + step.z());
                if (activeLevel.getBlockState(mutablePos).getLightDampening() > 1) return false;
            }
            return true;
        }

        private boolean setMax(int x, int y, int z, int candidate) {
            int previous = stagedAt(x, y, z);
            int next = PackedRgbLight.componentMax(previous, candidate);
            return next != previous && setStaged(x, y, z, next);
        }

        private record Source(BlockKey position, char packed) {
        }

        private boolean complete() {
            return phase == DONE;
        }
    }

    /** Fast additive propagation used for a newly placed source before a full correction arrives. */
    private static final class ImmediatePropagationTask {
        private final ArrayDeque<PropagationNode> queue = new ArrayDeque<>();
        private final BlockKey source;
        private final char sourcePacked;
        private boolean complete;

        private ImmediatePropagationTask(BlockKey source, char packed) {
            this.source = source;
            this.sourcePacked = packed;
            queue.add(new PropagationNode(source, packed, 0.0f, 0.0f, source, true));
        }

        private int process(int budget) {
            Character currentSource = sources.get(source);
            if (currentSource == null || currentSource != sourcePacked) {
                queue.clear();
                complete = true;
                return 1;
            }
            int consumed = 0;
            while (!queue.isEmpty() && consumed < budget) {
                PropagationNode current = queue.removeFirst();
                if (current.packed != 0) {
                    for (RgbLightAttenuation.Step step : RgbLightAttenuation.ROUND_STEPS) {
                        propagate(current, step);
                    }
                }
                consumed++;
            }
            complete = queue.isEmpty();
            return consumed;
        }

        private void propagate(PropagationNode from, RgbLightAttenuation.Step step) {
            int x = from.position.x + step.x();
            int y = from.position.y + step.y();
            int z = from.position.z + step.z();
            if (activeLevel == null || !activeLevel.isInsideBuildHeight(y) || !loaded(x, z)) return;
            if (step.diagonal() && !diagonalPathOpen(from.position, step)) return;
            mutablePos.set(x, y, z);
            BlockState destination = activeLevel.getBlockState(mutablePos);
            float nextPenalty = from.occlusionPenalty + RgbLightAttenuation.obstaclePenalty(destination);
            float nextDistance = Math.max(
                    from.distance, visualDistance(from.origin, x, y, z) + nextPenalty
            );
            if (nextDistance > PackedRgbLight.MAX_CHANNEL + 1.0f) return;
            int candidate = ClientRgbVisualLightSource.attenuateRounded(
                    from.packed, from.distance, nextDistance
            );
            int previous = getLocalPacked(x, y, z);
            int next = PackedRgbLight.componentMax(previous, candidate);
            if (candidate != 0 && next != previous && setPacked(x, y, z, next)) {
                queue.add(new PropagationNode(
                        new BlockKey(x, y, z), candidate, nextDistance, nextPenalty, from.origin, true
                ));
            }
        }

        private boolean diagonalPathOpen(BlockKey from, RgbLightAttenuation.Step step) {
            if (step.x() != 0) {
                mutablePos.set(from.x + step.x(), from.y, from.z);
                if (activeLevel.getBlockState(mutablePos).getLightDampening() > 1) return false;
            }
            if (step.y() != 0) {
                mutablePos.set(from.x, from.y + step.y(), from.z);
                if (activeLevel.getBlockState(mutablePos).getLightDampening() > 1) return false;
            }
            if (step.z() != 0) {
                mutablePos.set(from.x, from.y, from.z + step.z());
                if (activeLevel.getBlockState(mutablePos).getLightDampening() > 1) return false;
            }
            return true;
        }
    }

    private static float visualDistance(BlockKey origin, int x, int y, int z) {
        if (origin == null) return 0.0f;
        return RgbLightAttenuation.radialDistance(origin.x, origin.y, origin.z, x, y, z);
    }
}
