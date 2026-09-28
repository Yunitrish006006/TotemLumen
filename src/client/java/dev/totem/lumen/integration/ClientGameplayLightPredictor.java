package dev.totem.lumen.integration;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.gameplay.light.PackedRgbLight;
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
    private static final Map<ChunkKey, ChunkSourceState> chunkSourceStates = new HashMap<>();
    private static final Map<ChunkKey, Long> chunkGenerations = new HashMap<>();
    private static final Set<ChunkKey> reseedAfterRuleChange = new HashSet<>();
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
    private static boolean serverAuthoritative;
    private static boolean scanTurn = true;
    private static boolean immediateTurn = true;
    private static boolean changedThisTick;
    private static int loggedSourceEvents;
    private static int loggedInitialScans;
    private static int loggedRebuildInvalidations;
    private static int lastSkyRefreshKey = Integer.MIN_VALUE;
    private static long lastSkyClock = Long.MIN_VALUE;

    private ClientGameplayLightPredictor() {
    }

    public static void clear() {
        activeLevel = null;
        activeDimension = null;
        scans.clear();
        pendingScans.clear();
        chunkSourceStates.clear();
        chunkGenerations.clear();
        reseedAfterRuleChange.clear();
        sources.clear();
        sourcesBySection.clear();
        immediatePropagations.clear();
        pendingRebuilds.clear();
        queuedRebuilds.clear();
        knownSourceSections.clear();
        loggedInitialScans = 0;
        loggedRebuildInvalidations = 0;
        centerKnown = false;
        serverAuthoritative = false;
        scanTurn = true;
        immediateTurn = true;
        lastSkyRefreshKey = Integer.MIN_VALUE;
        lastSkyClock = Long.MIN_VALUE;
        ClientGameplayLightField.clearLocal();
        ClientGameplayLightField.clearSkyAffected();
    }

    public static void onChunkLoaded(ClientLevel level, LevelChunk chunk) {
        activate(level);
        enterServerAuthorityIfAvailable();
        LocalPlayer player = Minecraft.getInstance().player;
        if (!serverAuthoritative || (player != null && nearPlayerChunk(
                chunk.getPos().x(), chunk.getPos().z(), player
        ))) {
            enqueueScan(level, chunk, false);
        }
    }

    public static void onChunkUnloaded(ClientLevel level, LevelChunk chunk) {
        if (activeLevel != level) {
            return;
        }
        ChunkKey key = new ChunkKey(chunk.getPos().x(), chunk.getPos().z());
        bumpChunkGeneration(key);
        chunkSourceStates.remove(key);
        reseedAfterRuleChange.remove(key);
        ScanTask scan = scans.remove(key);
        if (scan != null) {
            scan.cancelled = true;
        }
        List<RemovedSource> removed = removeSourcesInChunk(key);
        immediatePropagations.removeIf(task -> Math.floorDiv(task.source.x, 16) == key.x
                && Math.floorDiv(task.source.z, 16) == key.z);
        ClientGameplayLightField.clearLocalChunk(activeDimension, key.x, key.z);
        for (RemovedSource source : removed) {
            scheduleInfluence(Region.aroundBlock(level, new BlockPos(
                    source.position.x, source.position.y, source.position.z
            )));
        }
    }

    public static void onBlockChanged(ClientLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!level.isInsideBuildHeight(pos.getY())) {
            return;
        }
        activate(level);
        enterServerAuthorityIfAvailable();
        bumpChunkGeneration(new ChunkKey(
                Math.floorDiv(pos.getX(), 16),
                Math.floorDiv(pos.getZ(), 16)
        ));
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
        if (sourceChanged || propagationChanged) {
            Region influence = Region.aroundBlock(level, pos);
            ClientGameplayLightField.markSpeculativeRegion(
                    activeDimension,
                    influence.minX, influence.minY, influence.minZ,
                    influence.maxX, influence.maxY, influence.maxZ
            );
        }
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
            Region affected = sourceChanged ? Region.aroundBlock(level, pos)
                    : new Region(pos.getX(), pos.getY(), pos.getZ(),
                            pos.getX(), pos.getY(), pos.getZ());
            refreshOverlappingRebuilds(List.of(affected));
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
                queueImmediatePropagation(key, nextSource, true);
            } else {
                immediatePropagations.removeIf(task -> task.source.equals(key));
                // Keep the last complete field visible until the replacement is ready.
                // Clearing the entire influence radius here made other nearby lights blink.
                scheduleInfluence(Region.aroundBlock(level, pos));
                ClientGameplayLightField.requestImmediateUpload();
            }
        } else if (propagationChanged) {
            scheduleInfluence(Region.aroundBlock(level, pos));
        }
    }

    public static void requestRebuild() {
        if (activeLevel == null) {
            return;
        }
        enterServerAuthorityIfAvailable();
        if (serverAuthoritative) {
            // The server will rebuild from the new rules and stream stable sections. Any old
            // local work uses the previous rules and must not overlay those sections later.
            clearPredictionWork();
            return;
        }
        // World-rule snapshots can arrive after the chunk scan. Re-evaluate the live states
        // before rebuilding, or already-placed lights keep their previous data-pack colors.
        Iterator<Map.Entry<BlockKey, Character>> iterator = sources.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockKey, Character> entry = iterator.next();
            BlockKey key = entry.getKey();
            mutablePos.set(key.x, key.y, key.z);
            char current = sourceFor(activeLevel.getBlockState(mutablePos));
            if (current == 0) {
                iterator.remove();
                unindexSource(key);
            } else {
                entry.setValue(current);
            }
        }
        ClientGameplayLightField.clearLocal();
        immediatePropagations.clear();
        pendingRebuilds.clear();
        queuedRebuilds.clear();
        reseedAfterRuleChange.clear();
        for (BlockKey source : sources.keySet()) {
            Region influence = Region.aroundBlock(activeLevel, new BlockPos(source.x, source.y, source.z));
            ClientGameplayLightField.markSpeculativeRegion(
                    activeDimension,
                    influence.minX, influence.minY, influence.minZ,
                    influence.maxX, influence.maxY, influence.maxZ
            );
            scheduleInfluence(influence);
            reseedAfterRuleChange.add(new ChunkKey(
                    Math.floorDiv(source.x, 16), Math.floorDiv(source.z, 16)
            ));
        }
        for (ChunkKey chunk : List.copyOf(chunkSourceStates.keySet())) {
            bumpChunkGeneration(chunk);
        }
        chunkSourceStates.clear();
        // A previous rule may have suppressed an emitter entirely, so it will not be in
        // `sources`. Revisit loaded nearby chunks on the next tick as well.
        centerKnown = false;
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
        long diagnosticStart = System.nanoTime();
        activate(level);
        enterServerAuthorityIfAvailable();
        ClientGameplayLightField.advancePredictionTick();
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
                if (serverAuthoritative && centerKnown) {
                    // Prediction is a near-player hint once stable server sync exists. Do not
                    // accumulate source scans and correction jobs from every visited chunk.
                    clearPredictionWork();
                }
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
            ScanTask scan = nextScan();
            RebuildTask rebuild = nextReadyRebuild();
            if (immediate != null && (immediateTurn || (scan == null && rebuild == null))) {
                used = immediate.process(Math.min(remaining, 4096));
                if (immediate.complete) {
                    immediatePropagations.removeFirst();
                }
                immediateTurn = false;
            } else if (scanBeforeRebuild(scan != null, rebuild != null, scanTurn)) {
                used = scan.process(Math.min(remaining, 4096));
                if (scan.complete) {
                    finishScan(scan);
                }
                scanTurn = false;
                immediateTurn = true;
            } else if (rebuild != null) {
                used = rebuild.process(Math.min(remaining, 4096));
                if (rebuild.complete()) {
                    boolean published = rebuild.publish();
                    pendingRebuilds.removeFirst();
                    queuedRebuilds.remove(rebuild.anchor);
                    if (!published) {
                        scheduleCoreRebuild(rebuild.anchor);
                    }
                }
                scanTurn = true;
                immediateTurn = true;
            } else {
                break;
            }
            if (used <= 0) {
                break;
            }
            remaining -= used;
        }
        ClientGameplayLightField.finishLocalBatch(changedThisTick);
        RgbLatencyDiagnostics.recordPredictor(
                System.nanoTime() - diagnosticStart,
                WORK_BUDGET - remaining,
                scans.size(),
                pendingRebuilds.size(),
                immediatePropagations.size()
        );
    }

    private static boolean scanBeforeRebuild(boolean hasScan, boolean hasRebuild, boolean scanTurn) {
        return hasScan && (scanTurn || !hasRebuild);
    }

    /** Build-time guard: old-world scans must progress alongside correction and immediate work. */
    static void verifyScanScheduling() {
        if (!scanBeforeRebuild(true, true, true)
                || scanBeforeRebuild(true, true, false)
                || !scanBeforeRebuild(true, false, false)
                || scanBeforeRebuild(false, true, true)) {
            throw new IllegalStateException("RGB chunk scans must alternate with region rebuilds");
        }
    }

    /** Build-time guard for overlapping correction regions across chunk boundaries. */
    static void verifyRebuildOverlap() {
        if (activeLevel != null || !pendingRebuilds.isEmpty() || !queuedRebuilds.isEmpty()) {
            throw new IllegalStateException("RGB rebuild verifier requires an unused predictor");
        }
        Region light = new Region(-17, 64, -1, 13, 94, 29);
        if (!light.intersects(new Region(13, 75, 0, 30, 90, 20))
                || light.intersects(new Region(14, 75, 0, 30, 90, 20))) {
            throw new IllegalStateException("RGB rebuilds must invalidate all overlapping light influence");
        }
        SectionKey anchor = new SectionKey(-1, 4, 0);
        try {
            pendingRebuilds.addLast(new RebuildTask(anchor, light));
            queuedRebuilds.add(anchor);
            if (refreshOverlappingRebuilds(List.of(new Region(14, 75, 0, 30, 90, 20))) != 0
                    || pendingRebuilds.size() != 1
                    || refreshOverlappingRebuilds(List.of(new Region(13, 75, 0, 30, 90, 20))) != 1
                    || !pendingRebuilds.isEmpty()) {
                throw new IllegalStateException("Stale RGB rebuilds must be invalidated on overlap");
            }
        } finally {
            pendingRebuilds.clear();
            queuedRebuilds.clear();
        }
    }

    /** Build-time guard for the first visible RGB sample from an existing-world emitter. */
    static void verifyDiscoveredSourceFastPath() {
        if (activeLevel != null || !sources.isEmpty() || !immediatePropagations.isEmpty()) {
            throw new IllegalStateException("RGB loaded-source verifier requires an unused predictor");
        }
        String previousDimension = activeDimension;
        BlockKey source = new BlockKey(-17, 64, 3);
        char packed = (char) 0xF321;
        try {
            activeDimension = "minecraft:overworld";
            publishDiscoveredSource(source, packed);
            if (ClientGameplayLightField.localPackedAt(activeDimension, source.x, source.y, source.z) != packed
                    || immediatePropagations.size() != 1) {
                throw new IllegalStateException("Existing RGB sources must seed the field before rebuild");
            }
        } finally {
            immediatePropagations.clear();
            ClientGameplayLightField.clearLocal();
            activeDimension = previousDimension;
            changedThisTick = false;
        }
    }

    /** Build-time guard: a newly placed source must not wait behind loaded-world emitters. */
    static void verifyLocalSourcePriority() {
        if (activeLevel != null || !immediatePropagations.isEmpty()) {
            throw new IllegalStateException("RGB source-priority verifier requires an unused predictor");
        }
        BlockKey discovered = new BlockKey(0, 64, 0);
        BlockKey placed = new BlockKey(1, 64, 0);
        try {
            queueImmediatePropagation(discovered, (char) 0xF321, false);
            queueImmediatePropagation(placed, (char) 0xF321, true);
            if (!immediatePropagations.peekFirst().source.equals(placed)) {
                throw new IllegalStateException("Local RGB edits must precede background emitters");
            }
        } finally {
            immediatePropagations.clear();
        }
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

    private static void enterServerAuthorityIfAvailable() {
        if (serverAuthoritative || activeDimension == null
                || !ClientGameplayLightField.hasServerField(activeDimension)) {
            return;
        }
        serverAuthoritative = true;
        clearPredictionWork();
    }

    private static void clearPredictionWork() {
        scans.clear();
        pendingScans.clear();
        chunkSourceStates.clear();
        chunkGenerations.clear();
        reseedAfterRuleChange.clear();
        sources.clear();
        sourcesBySection.clear();
        immediatePropagations.clear();
        pendingRebuilds.clear();
        queuedRebuilds.clear();
        knownSourceSections.clear();
        centerKnown = false;
        ClientGameplayLightField.clearLocal();
    }

    private static boolean nearPlayerChunk(int chunkX, int chunkZ, LocalPlayer player) {
        int playerChunkX = Math.floorDiv(player.blockPosition().getX(), 16);
        int playerChunkZ = Math.floorDiv(player.blockPosition().getZ(), 16);
        return Math.abs(chunkX - playerChunkX) <= CHUNK_RADIUS
                && Math.abs(chunkZ - playerChunkZ) <= CHUNK_RADIUS;
    }

    private static void enqueueVisibleChunks(ClientLevel level) {
        // addFirst in outer-to-inner order puts the player's own chunk ahead of the
        // potentially large FIFO accumulated while an existing world was loading.
        for (int radius = CHUNK_RADIUS; radius >= 0; radius--) {
            for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                for (int offsetX = -radius; offsetX <= radius; offsetX++) {
                    if (Math.max(Math.abs(offsetX), Math.abs(offsetZ)) != radius) {
                        continue;
                    }
                    int chunkX = centerChunkX + offsetX;
                    int chunkZ = centerChunkZ + offsetZ;
                    LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                    if (chunk != null) {
                        enqueueScan(level, chunk, true);
                    }
                }
            }
        }
    }

    private static void enqueueScan(ClientLevel level, LevelChunk chunk, boolean priority) {
        ChunkKey key = new ChunkKey(chunk.getPos().x(), chunk.getPos().z());
        if (priority
                && chunkSourceStates.get(key) == ChunkSourceState.READY
                && !reseedAfterRuleChange.contains(key)) {
            return;
        }
        bumpChunkGeneration(key);
        chunkSourceStates.put(key, ChunkSourceState.SCANNING);
        ScanTask old = scans.remove(key);
        if (old != null) {
            old.cancelled = true;
        }
        // A rescan must not remove still-present emitters before the scan finishes. Doing so
        // temporarily drops their RGB contribution and makes lights flash as chunks are revisited.
        ScanTask scan = new ScanTask(level, chunk);
        scans.put(key, scan);
        if (priority) {
            pendingScans.addFirst(scan);
        } else {
            pendingScans.addLast(scan);
        }
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

    private static RebuildTask nextReadyRebuild() {
        int attempts = pendingRebuilds.size();
        while (attempts-- > 0 && !pendingRebuilds.isEmpty()) {
            RebuildTask task = pendingRebuilds.removeFirst();
            if (task.started || regionSourcesReady(task.region)) {
                pendingRebuilds.addFirst(task);
                return task;
            }
            pendingRebuilds.addLast(task);
        }
        return null;
    }

    private static void finishScan(ScanTask scan) {
        pendingScans.removeFirstOccurrence(scan);
        scans.remove(scan.chunkKey, scan);
        chunkSourceStates.put(scan.chunkKey, ChunkSourceState.READY);
        bumpChunkGeneration(scan.chunkKey);
        boolean reseed = reseedAfterRuleChange.remove(scan.chunkKey);
        Map<BlockKey, Character> observed = new HashMap<>();
        Map<SectionKey, Region> corrections = new HashMap<>();
        List<Region> changedInfluences = new ArrayList<>();
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
                Region influence = Region.aroundBlock(activeLevel,
                        new BlockPos(source.x, source.y, source.z));
                changedInfluences.add(influence);
                corrections.merge(SectionKey.fromBlock(source.x, source.y, source.z),
                        influence, Region::union);
            }
        }
        for (Map.Entry<BlockKey, Character> entry : observed.entrySet()) {
            BlockKey source = entry.getKey();
            char current = entry.getValue();
            Character previous = putSource(source, current);
            if (previous == null || previous != current || reseed) {
                if (previous == null || reseed) {
                    // Loaded-world sources need the same fast visual path as newly placed
                    // sources; otherwise their first light waits for a full region rebuild.
                    publishDiscoveredSource(source, current);
                }
                knownSourceSections.add(SectionKey.fromBlock(source.x, source.y, source.z));
                Region influence = Region.aroundBlock(activeLevel,
                        new BlockPos(source.x, source.y, source.z));
                if (previous == null || previous != current) {
                    changedInfluences.add(influence);
                }
                corrections.merge(SectionKey.fromBlock(source.x, source.y, source.z),
                        influence, Region::union);
            }
        }
        // Every pending rebuild captured a source snapshot when it was enqueued. A later
        // chunk scan can discover another emitter inside its output region; publishing the
        // old snapshot after the new source's fast pass would erase that light indefinitely.
        int invalidated = refreshOverlappingRebuilds(changedInfluences);
        for (Region correction : corrections.values()) {
            scheduleInfluence(correction);
        }
        if (invalidated > 0 && loggedRebuildInvalidations++ < 4) {
            TotemLumenClient.LOGGER.info(
                    "RGB stale rebuilds restarted: chunk=({},{}), count={}",
                    scan.chunkKey.x, scan.chunkKey.z, invalidated
            );
        }
        if (!observed.isEmpty() && loggedInitialScans++ < 4) {
            TotemLumenClient.LOGGER.info(
                    "RGB existing chunk sources discovered: chunk=({},{}), count={}",
                    scan.chunkKey.x, scan.chunkKey.z, observed.size()
            );
        }
    }

    private static void publishDiscoveredSource(BlockKey source, char packed) {
        int oldPacked = getLocalPacked(source.x, source.y, source.z);
        setPacked(source.x, source.y, source.z, PackedRgbLight.componentMax(oldPacked, packed));
        queueImmediatePropagation(source, packed, false);
    }

    private static void queueImmediatePropagation(BlockKey source, char packed, boolean localEdit) {
        ImmediatePropagationTask task = new ImmediatePropagationTask(source, packed);
        if (localEdit) {
            immediatePropagations.addFirst(task);
        } else {
            immediatePropagations.addLast(task);
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
        result.sort(Comparator
                .comparingInt((RebuildTask.Source source) -> source.position.y)
                .thenComparingInt(source -> source.position.z)
                .thenComparingInt(source -> source.position.x));
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

    private static int refreshOverlappingRebuilds(List<Region> changedInfluences) {
        if (changedInfluences.isEmpty()) {
            return 0;
        }
        Set<SectionKey> stale = new HashSet<>();
        pendingRebuilds.removeIf(task -> {
            boolean overlaps = false;
            for (Region influence : changedInfluences) {
                if (task.region.intersects(influence)) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                return false;
            }
            stale.add(task.anchor);
            queuedRebuilds.remove(task.anchor);
            return true;
        });
        stale.forEach(ClientGameplayLightPredictor::rescheduleCoreRebuild);
        return stale.size();
    }

    private static void scheduleInfluence(Region influence) {
        if (activeLevel == null) {
            return;
        }
        for (int sectionY = Math.floorDiv(influence.minY, 16); sectionY <= Math.floorDiv(influence.maxY, 16); sectionY++) {
            for (int sectionZ = Math.floorDiv(influence.minZ, 16); sectionZ <= Math.floorDiv(influence.maxZ, 16); sectionZ++) {
                for (int sectionX = Math.floorDiv(influence.minX, 16); sectionX <= Math.floorDiv(influence.maxX, 16); sectionX++) {
                    scheduleCoreRebuild(new SectionKey(sectionX, sectionY, sectionZ));
                }
            }
        }
    }

    private static void scheduleCoreRebuild(SectionKey anchor) {
        if (activeLevel == null || !loaded(anchor.x * 16, anchor.z * 16)) {
            return;
        }
        if (queuedRebuilds.add(anchor)) {
            pendingRebuilds.addLast(new RebuildTask(anchor, Region.around(activeLevel, anchor)));
        }
    }

    private static void rescheduleCoreRebuild(SectionKey anchor) {
        if (activeLevel == null || !loaded(anchor.x * 16, anchor.z * 16)) {
            return;
        }
        pendingRebuilds.removeIf(task -> task.anchor.equals(anchor));
        queuedRebuilds.remove(anchor);
        if (queuedRebuilds.add(anchor)) {
            pendingRebuilds.addFirst(new RebuildTask(anchor, Region.around(activeLevel, anchor)));
        }
    }

    private static boolean regionSourcesReady(Region region) {
        if (serverAuthoritative) {
            // Local work now contains only speculative block edits. The server field supplies
            // all pre-existing emitters, so no client chunk source scan is required.
            return true;
        }
        for (int chunkZ = Math.floorDiv(region.minZ, 16); chunkZ <= Math.floorDiv(region.maxZ, 16); chunkZ++) {
            for (int chunkX = Math.floorDiv(region.minX, 16); chunkX <= Math.floorDiv(region.maxX, 16); chunkX++) {
                if (!loaded(chunkX << 4, chunkZ << 4)) {
                    continue;
                }
                if (chunkSourceStates.get(new ChunkKey(chunkX, chunkZ)) != ChunkSourceState.READY) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Map<ChunkKey, ChunkInputStamp> captureChunkInputs(Region region) {
        Map<ChunkKey, ChunkInputStamp> result = new HashMap<>();
        for (int chunkZ = Math.floorDiv(region.minZ, 16); chunkZ <= Math.floorDiv(region.maxZ, 16); chunkZ++) {
            for (int chunkX = Math.floorDiv(region.minX, 16); chunkX <= Math.floorDiv(region.maxX, 16); chunkX++) {
                ChunkKey key = new ChunkKey(chunkX, chunkZ);
                result.put(key, new ChunkInputStamp(
                        chunkGenerations.getOrDefault(key, 0L),
                        loaded(chunkX << 4, chunkZ << 4),
                        chunkSourceStates.get(key)
                ));
            }
        }
        return result;
    }

    private static boolean chunkInputsCurrent(Map<ChunkKey, ChunkInputStamp> snapshot) {
        for (Map.Entry<ChunkKey, ChunkInputStamp> entry : snapshot.entrySet()) {
            ChunkKey key = entry.getKey();
            ChunkInputStamp current = new ChunkInputStamp(
                    chunkGenerations.getOrDefault(key, 0L),
                    loaded(key.x << 4, key.z << 4),
                    chunkSourceStates.get(key)
            );
            if (!current.equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static void bumpChunkGeneration(ChunkKey key) {
        chunkGenerations.merge(key, 1L, Long::sum);
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

    private enum ChunkSourceState {
        SCANNING,
        READY
    }

    private record ChunkInputStamp(long generation, boolean loaded, ChunkSourceState state) {
    }

    private record ChunkKey(int x, int z) {
    }

    private record BlockKey(int x, int y, int z) {
    }

    private record PropagationNode(BlockKey position, int packed) {
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

        boolean intersects(Region other) {
            return minX <= other.maxX && maxX >= other.minX
                    && minY <= other.maxY && maxY >= other.minY
                    && minZ <= other.maxZ && maxZ >= other.minZ;
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
        private static final int PROPAGATE = 1;
        private static final int DONE = 2;

        private final SectionKey anchor;
        private final Region region;
        private List<Source> sourceSnapshot = List.of();
        private Map<ChunkKey, ChunkInputStamp> inputSnapshot = Map.of();
        private boolean started;
        private final ArrayDeque<PropagationNode> queue = new ArrayDeque<>();
        private final Map<ClientGameplayLightField.SectionCoordinate, char[]> staged = new HashMap<>();
        private int phase = SOURCES;
        private int sourceCursor;

        private RebuildTask(SectionKey anchor, Region region) {
            this.anchor = anchor;
            this.region = region;
        }

        private void start() {
            if (started) {
                return;
            }
            sourceSnapshot = sourcesIn(region);
            inputSnapshot = captureChunkInputs(region);
            started = true;
        }

        private int process(int budget) {
            start();
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
                            queue.add(new PropagationNode(source.position, source.packed));
                        }
                        consumed++;
                    }
                    if (sourceCursor >= sourceSnapshot.size()) phase = PROPAGATE;
                } else {
                    while (!queue.isEmpty() && consumed < budget) {
                        PropagationNode current = queue.removeFirst();
                        if (current.packed != 0) {
                            propagate(current, -1, 0, 0);
                            propagate(current, 1, 0, 0);
                            propagate(current, 0, -1, 0);
                            propagate(current, 0, 1, 0);
                            propagate(current, 0, 0, -1);
                            propagate(current, 0, 0, 1);
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

        private boolean publish() {
            if (!chunkInputsCurrent(inputSnapshot)) {
                return false;
            }
            int minX = anchor.x * 16;
            int minY = Math.max(activeLevel.getMinY(), anchor.y * 16);
            int minZ = anchor.z * 16;
            int maxX = minX + 15;
            int maxY = Math.min(activeLevel.getMaxY() - 1, minY + 15);
            int maxZ = minZ + 15;
            ClientGameplayLightField.replaceLocalRegion(
                    activeDimension,
                    minX, minY, minZ,
                    maxX, maxY, maxZ,
                    staged
            );
            return true;
        }

        private void propagate(PropagationNode from, int dx, int dy, int dz) {
            int x = from.position.x + dx;
            int y = from.position.y + dy;
            int z = from.position.z + dz;
            if (!region.contains(x, y, z) || !activeLevel.isInsideBuildHeight(y) || !loaded(x, z)) return;
            mutablePos.set(x, y, z);
            int candidate = ClientRgbVisualLightSource.attenuate(
                    from.packed, activeLevel.getBlockState(mutablePos)
            );
            if (candidate != 0 && setMax(x, y, z, candidate)) {
                queue.add(new PropagationNode(new BlockKey(x, y, z), candidate));
            }
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
            queue.add(new PropagationNode(source, packed));
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
                    propagate(current, -1, 0, 0);
                    propagate(current, 1, 0, 0);
                    propagate(current, 0, -1, 0);
                    propagate(current, 0, 1, 0);
                    propagate(current, 0, 0, -1);
                    propagate(current, 0, 0, 1);
                }
                consumed++;
            }
            complete = queue.isEmpty();
            return consumed;
        }

        private void propagate(PropagationNode from, int dx, int dy, int dz) {
            int x = from.position.x + dx;
            int y = from.position.y + dy;
            int z = from.position.z + dz;
            if (activeLevel == null || !activeLevel.isInsideBuildHeight(y) || !loaded(x, z)) return;
            mutablePos.set(x, y, z);
            int candidate = ClientRgbVisualLightSource.attenuate(
                    from.packed, activeLevel.getBlockState(mutablePos)
            );
            int previous = getLocalPacked(x, y, z);
            int next = PackedRgbLight.componentMax(previous, candidate);
            if (candidate != 0 && next != previous && setPacked(x, y, z, next)) {
                queue.add(new PropagationNode(new BlockKey(x, y, z), candidate));
            }
        }
    }

}
