package dev.totem.lumen.gameplay.light;

import dev.totem.lumen.TotemLumen;
import dev.totem.lumen.world.LightingWorldRulesReloadListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collection;

/**
 * Server-thread authoritative RGB gameplay-light field for one ServerLevel.
 *
 * <p>This is intentionally a low-cost Minecraft-style light field rather than a renderer: 0..15
 * RGB channels, component-wise max, at least one level of attenuation per block, no bounce/GI, and
 * a fixed per-tick work/time budget.</p>
 */
public final class ServerGameplayLightEngine {
    private static final int WORK_SLICE = 1024;
    private static final int METRICS_INTERVAL_TICKS = 600;

    private final ServerLevel level;
    private final Map<ServerSectionKey, ServerLightSection> sections = new HashMap<>();
    private final Map<ServerSectionKey, Map<ServerBlockKey, Character>> sources = new HashMap<>();
    private final Map<ServerSectionKey, Integer> dirtySections = new HashMap<>();
    private final Set<ServerSectionKey> changedSections = new LinkedHashSet<>();

    private final ArrayDeque<ServerSectionKey> pendingAnchors = new ArrayDeque<>();
    private final Set<ServerSectionKey> pendingAnchorSet = new HashSet<>();
    private final Set<ServerSectionKey> rerunAnchors = new HashSet<>();

    private final ArrayDeque<ChunkScanTask> pendingScans = new ArrayDeque<>();
    private final Map<ChunkKey, ChunkScanTask> scansByChunk = new HashMap<>();

    private LightRebuildTask activeRebuild;
    private long ticks;
    private long accumulatedTickNanos;
    private long maxTickNanos;
    private long accumulatedWork;
    private int measuredTicks;

    public ServerGameplayLightEngine(ServerLevel level) {
        this.level = level;
    }

    public ServerLevel level() {
        return level;
    }

    public int packedBlockLight(BlockPos pos) {
        if (!level.isInsideBuildHeight(pos.getY())) {
            return 0;
        }
        ServerLightSection section = sections.get(ServerSectionKey.fromBlock(pos));
        if (section == null) {
            return 0;
        }
        return section.get(pos.getX(), pos.getY(), pos.getZ());
    }

    public boolean isStable(BlockPos pos) {
        return !dirtySections.containsKey(ServerSectionKey.fromBlock(pos));
    }

    public int allocatedSectionCount() {
        return sections.size();
    }

    public int indexedSourceCount() {
        int total = 0;
        for (Map<ServerBlockKey, Character> bucket : sources.values()) {
            total += bucket.size();
        }
        return total;
    }

    public int pendingRebuildCount() {
        return pendingAnchors.size() + (activeRebuild == null ? 0 : 1);
    }

    public int pendingScanCount() {
        return scansByChunk.size();
    }

    public int dirtySectionCount() {
        return dirtySections.size();
    }

    /** Returns the current RGB field for every allocated section, used for an initial client sync. */
    public List<SectionSnapshot> snapshotSections() {
        List<SectionSnapshot> result = new ArrayList<>(sections.size());
        for (Map.Entry<ServerSectionKey, ServerLightSection> entry : sections.entrySet()) {
            if (dirtySections.containsKey(entry.getKey())) {
                continue;
            }
            result.add(snapshot(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    /** Drains changed sections after budgeted propagation so clients receive stable field data. */
    public List<SectionSnapshot> drainChangedSections() {
        if (changedSections.isEmpty()) {
            return List.of();
        }
        List<SectionSnapshot> result = new ArrayList<>(changedSections.size());
        Iterator<ServerSectionKey> iterator = changedSections.iterator();
        while (iterator.hasNext()) {
            ServerSectionKey key = iterator.next();
            // A rebuild clears and reseeds a region incrementally. Do not publish its transient
            // empty/intermediate values; otherwise the client briefly loses RGB, then receives
            // it again when propagation reaches the same section. Publish only stable sections.
            if (dirtySections.containsKey(key)) {
                continue;
            }
            ServerLightSection section = sections.get(key);
            result.add(new SectionSnapshot(
                    key.x(), key.y(), key.z(), section == null ? new char[ServerLightSection.VOXEL_COUNT] : section.copyValues()
            ));
            iterator.remove();
        }
        return result;
    }

    private static SectionSnapshot snapshot(ServerSectionKey key, ServerLightSection section) {
        return new SectionSnapshot(key.x(), key.y(), key.z(), section.copyValues());
    }

    public record SectionSnapshot(int x, int y, int z, char[] values) {
    }

    /** Queue a budgeted source scan for a newly loaded chunk. */
    public void onChunkLoaded(LevelChunk chunk) {
        ChunkKey key = new ChunkKey(chunk.getPos().x(), chunk.getPos().z());
        ChunkScanTask old = scansByChunk.remove(key);
        if (old != null) {
            old.cancel();
            old.releaseDirtyMarks();
        }

        ChunkScanTask task = new ChunkScanTask(chunk);
        scansByChunk.put(key, task);
        pendingScans.addLast(task);
    }

    /** Remove unloaded storage immediately, then relight loaded neighbors affected by removed sources. */
    public void onChunkUnloaded(LevelChunk chunk) {
        int chunkX = chunk.getPos().x();
        int chunkZ = chunk.getPos().z();
        ChunkKey chunkKey = new ChunkKey(chunkX, chunkZ);

        ChunkScanTask scan = scansByChunk.remove(chunkKey);
        if (scan != null) {
            scan.cancel();
            scan.releaseDirtyMarks();
        }

        Set<ServerSectionKey> removedSourceSections = new LinkedHashSet<>();
        Iterator<Map.Entry<ServerSectionKey, Map<ServerBlockKey, Character>>> sourceIterator =
                sources.entrySet().iterator();
        while (sourceIterator.hasNext()) {
            Map.Entry<ServerSectionKey, Map<ServerBlockKey, Character>> entry = sourceIterator.next();
            ServerSectionKey key = entry.getKey();
            if (key.x() == chunkX && key.z() == chunkZ) {
                if (!entry.getValue().isEmpty()) {
                    removedSourceSections.add(key);
                }
                sourceIterator.remove();
            }
        }

        Set<ServerSectionKey> removedSections = sections.keySet().stream()
                .filter(key -> key.x() == chunkX && key.z() == chunkZ)
                .collect(java.util.stream.Collectors.toSet());
        changedSections.addAll(removedSections);
        sections.keySet().removeAll(removedSections);
        dirtySections.keySet().removeIf(key -> key.x() == chunkX && key.z() == chunkZ);

        for (ServerSectionKey key : removedSourceSections) {
            scheduleRebuild(key);
        }
    }

    /** React to an authoritative server BlockState update without synchronously propagating light. */
    public void onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState) {
        if (!level.isInsideBuildHeight(pos.getY())) {
            return;
        }

        boolean previousEmitter = oldState.getLightEmission() > 0;
        boolean nextEmitter = newState.getLightEmission() > 0;
        char previousSource = sourceForState(oldState);
        char nextSource = sourceForState(newState);
        int previousDampening = oldState.getLightDampening();
        int nextDampening = newState.getLightDampening();

        updateIndexedSource(ServerBlockKey.from(pos), nextSource, nextEmitter);
        if (previousSource != nextSource
                || previousEmitter != nextEmitter
                || previousDampening != nextDampening) {
            scheduleRebuild(ServerSectionKey.fromBlock(pos));
        }
    }

    /** Re-resolve every indexed source after a successful data-pack reload. */
    public void refreshWorldRules() {
        Set<ServerSectionKey> changedAnchors = new LinkedHashSet<>();
        List<ServerBlockKey> positions = new ArrayList<>();
        for (Map<ServerBlockKey, Character> bucket : sources.values()) {
            positions.addAll(bucket.keySet());
        }

        for (ServerBlockKey sourcePos : positions) {
            BlockPos pos = sourcePos.toBlockPos();
            if (!level.hasChunkAt(pos)) {
                continue;
            }
            char previous = indexedSource(sourcePos);
            BlockState state = level.getBlockState(pos);
            boolean stillEmitter = state.getLightEmission() > 0;
            char next = sourceForState(state);
            if (previous != next || !stillEmitter) {
                updateIndexedSource(sourcePos, next, stillEmitter);
                changedAnchors.add(ServerSectionKey.fromBlock(sourcePos.x(), sourcePos.y(), sourcePos.z()));
            }
        }

        for (ServerSectionKey anchor : changedAnchors) {
            scheduleRebuild(anchor);
        }
        TotemLumen.LOGGER.info(
                "Queued {} gameplay-light section rebuild(s) in {} after lighting-rule reload",
                changedAnchors.size(),
                level.dimension().identifier()
        );
    }

    public boolean hasPendingWork() {
        return activeRebuild != null || !pendingAnchors.isEmpty() || nextScan() != null;
    }

    /** Process a bounded share of the server-wide gameplay-lighting budget. */
    public int tick(int workBudget, long timeBudgetNanos) {
        if (workBudget <= 0 || timeBudgetNanos <= 0L) {
            return 0;
        }
        long start = System.nanoTime();
        int remaining = workBudget;
        int work = 0;
        boolean scanTurn = true;

        while (remaining > 0 && System.nanoTime() - start < timeBudgetNanos) {
            int slice = Math.min(WORK_SLICE, remaining);
            int consumed = 0;

            ChunkScanTask scan = nextScan();
            if (activeRebuild == null && !pendingAnchors.isEmpty()) {
                beginNextRebuild();
            }

            if (scan != null && (scanTurn || activeRebuild == null)) {
                consumed = scan.process(slice);
                if (scan.isComplete()) {
                    finishScan(scan);
                }
            } else if (activeRebuild != null) {
                consumed = activeRebuild.process(slice);
                if (activeRebuild.isComplete()) {
                    finishActiveRebuild();
                }
            } else if (scan != null) {
                consumed = scan.process(slice);
                if (scan.isComplete()) {
                    finishScan(scan);
                }
            }

            if (consumed <= 0) {
                break;
            }
            scanTurn = !scanTurn;
            remaining -= consumed;
            work += consumed;
        }

        long elapsed = System.nanoTime() - start;
        ticks++;
        accumulatedTickNanos += elapsed;
        maxTickNanos = Math.max(maxTickNanos, elapsed);
        accumulatedWork += work;
        measuredTicks++;

        if (ticks % METRICS_INTERVAL_TICKS == 0) {
            double averageMicros = measuredTicks == 0
                    ? 0.0
                    : (accumulatedTickNanos / 1_000.0) / measuredTicks;
            double maxMicros = maxTickNanos / 1_000.0;
            long averageWork = measuredTicks == 0 ? 0 : accumulatedWork / measuredTicks;
            TotemLumen.LOGGER.debug(
                    "Gameplay lighting {}: rgbSections={}, sources={}, dirtySections={}, rebuilds={}, scans={}, "
                            + "avgTick={}us, maxTick={}us, avgWork={}",
                    level.dimension().identifier(),
                    allocatedSectionCount(),
                    indexedSourceCount(),
                    dirtySectionCount(),
                    pendingRebuildCount(),
                    pendingScanCount(),
                    Math.round(averageMicros),
                    Math.round(maxMicros),
                    averageWork
            );
            accumulatedTickNanos = 0L;
            maxTickNanos = 0L;
            accumulatedWork = 0L;
            measuredTicks = 0;
        }
        return work;
    }

    public void clear() {
        sections.clear();
        changedSections.clear();
        sources.clear();
        dirtySections.clear();
        pendingAnchors.clear();
        pendingAnchorSet.clear();
        rerunAnchors.clear();
        for (ChunkScanTask scan : scansByChunk.values()) {
            scan.cancel();
        }
        pendingScans.clear();
        scansByChunk.clear();
        activeRebuild = null;
    }

    private ChunkScanTask nextScan() {
        while (!pendingScans.isEmpty()) {
            ChunkScanTask scan = pendingScans.peekFirst();
            if (scan.isCancelled()) {
                pendingScans.removeFirst();
                continue;
            }
            return scan;
        }
        return null;
    }

    private void finishScan(ChunkScanTask scan) {
        pendingScans.removeFirstOccurrence(scan);
        scansByChunk.remove(scan.chunkKey(), scan);
        scan.finish();
    }

    private void beginNextRebuild() {
        while (!pendingAnchors.isEmpty()) {
            ServerSectionKey anchor = pendingAnchors.removeFirst();
            pendingAnchorSet.remove(anchor);
            LightRebuildRegion region = LightRebuildRegion.around(level, anchor);
            activeRebuild = new LightRebuildTask(anchor, region, snapshotSources(region));
            return;
        }
    }

    private void finishActiveRebuild() {
        ServerSectionKey anchor = activeRebuild.anchor;
        LightRebuildRegion region = activeRebuild.region;
        pruneEmptySections(region);
        markDirty(region, -1);
        activeRebuild = null;

        if (rerunAnchors.remove(anchor)) {
            pendingAnchorSet.add(anchor);
            pendingAnchors.addLast(anchor);
            // The rerun acquired its dirty-region reference when it was requested.
        }
    }

    private void scheduleRebuild(ServerSectionKey anchor) {
        LightRebuildRegion region = LightRebuildRegion.around(level, anchor);
        if (activeRebuild != null && activeRebuild.anchor.equals(anchor)) {
            if (rerunAnchors.add(anchor)) {
                markDirty(region, 1);
            }
            return;
        }
        if (pendingAnchorSet.add(anchor)) {
            pendingAnchors.addLast(anchor);
            markDirty(region, 1);
        }
    }

    private void markDirty(LightRebuildRegion region, int delta) {
        for (int sectionY = region.minSectionY(); sectionY <= region.maxSectionY(); sectionY++) {
            for (int sectionZ = region.minSectionZ(); sectionZ <= region.maxSectionZ(); sectionZ++) {
                for (int sectionX = region.minSectionX(); sectionX <= region.maxSectionX(); sectionX++) {
                    ServerSectionKey key = new ServerSectionKey(sectionX, sectionY, sectionZ);
                    if (delta > 0) {
                        dirtySections.merge(key, delta, Integer::sum);
                    } else {
                        Integer count = dirtySections.get(key);
                        if (count == null || count <= 1) {
                            dirtySections.remove(key);
                        } else {
                            dirtySections.put(key, count - 1);
                        }
                    }
                }
            }
        }
    }

    private void markSectionDirty(ServerSectionKey key, int delta) {
        if (delta > 0) {
            dirtySections.merge(key, delta, Integer::sum);
            return;
        }
        Integer count = dirtySections.get(key);
        if (count == null || count <= 1) {
            dirtySections.remove(key);
        } else {
            dirtySections.put(key, count - 1);
        }
    }

    private List<LightSource> snapshotSources(LightRebuildRegion region) {
        List<LightSource> result = new ArrayList<>();
        for (int sectionY = region.minSectionY(); sectionY <= region.maxSectionY(); sectionY++) {
            for (int sectionZ = region.minSectionZ(); sectionZ <= region.maxSectionZ(); sectionZ++) {
                for (int sectionX = region.minSectionX(); sectionX <= region.maxSectionX(); sectionX++) {
                    Map<ServerBlockKey, Character> bucket = sources.get(
                            new ServerSectionKey(sectionX, sectionY, sectionZ)
                    );
                    if (bucket == null) {
                        continue;
                    }
                    for (Map.Entry<ServerBlockKey, Character> entry : bucket.entrySet()) {
                        ServerBlockKey pos = entry.getKey();
                        if (region.contains(pos.x(), pos.y(), pos.z()) && entry.getValue() != 0) {
                            result.add(new LightSource(pos, entry.getValue()));
                        }
                    }
                }
            }
        }
        return result;
    }

    private char sourceForState(BlockState state) {
        var blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return GameplayLightSource.packedFor(
                state,
                LightingWorldRulesReloadListener.currentRules().ruleFor(blockId)
        );
    }

    private void updateIndexedSource(ServerBlockKey pos, char packed, boolean keepEmitter) {
        ServerSectionKey sectionKey = ServerSectionKey.fromBlock(pos.x(), pos.y(), pos.z());
        if (!keepEmitter) {
            Map<ServerBlockKey, Character> bucket = sources.get(sectionKey);
            if (bucket != null) {
                bucket.remove(pos);
                if (bucket.isEmpty()) {
                    sources.remove(sectionKey);
                }
            }
            return;
        }
        // Keep vanilla-emissive blocks indexed even when a data-pack rule sets gameplay_strength=0.
        // A later /reload can then re-enable gameplay light without rescanning every loaded voxel.
        sources.computeIfAbsent(sectionKey, ignored -> new HashMap<>()).put(pos, packed);
    }

    private char indexedSource(ServerBlockKey pos) {
        Map<ServerBlockKey, Character> bucket = sources.get(
                ServerSectionKey.fromBlock(pos.x(), pos.y(), pos.z())
        );
        if (bucket == null) {
            return 0;
        }
        return bucket.getOrDefault(pos, (char) 0);
    }

    private int getPacked(int x, int y, int z) {
        ServerLightSection section = sections.get(ServerSectionKey.fromBlock(x, y, z));
        return section == null ? 0 : section.get(x, y, z);
    }

    private boolean setPacked(int x, int y, int z, int packed) {
        ServerSectionKey key = ServerSectionKey.fromBlock(x, y, z);
        ServerLightSection section = sections.get(key);
        if (packed == 0) {
            boolean changed = section != null && section.set(x, y, z, 0);
            if (changed) changedSections.add(key);
            return changed;
        }
        if (section == null) {
            section = new ServerLightSection();
            sections.put(key, section);
        }
        boolean changed = section.set(x, y, z, packed);
        if (changed) changedSections.add(key);
        return changed;
    }

    private void pruneEmptySections(LightRebuildRegion region) {
        for (int sectionY = region.minSectionY(); sectionY <= region.maxSectionY(); sectionY++) {
            for (int sectionZ = region.minSectionZ(); sectionZ <= region.maxSectionZ(); sectionZ++) {
                for (int sectionX = region.minSectionX(); sectionX <= region.maxSectionX(); sectionX++) {
                    ServerSectionKey key = new ServerSectionKey(sectionX, sectionY, sectionZ);
                    ServerLightSection section = sections.get(key);
                    if (section != null && section.isEmpty()) {
                        sections.remove(key);
                    }
                }
            }
        }
    }

    private boolean loaded(int blockX, int blockZ) {
        return level.hasChunkAt(blockX, blockZ);
    }

    private final class ChunkScanTask {
        private final LevelChunk chunk;
        private final ChunkKey chunkKey;
        private final LevelChunkSection[] chunkSections;
        private final List<ServerSectionKey> dirtyMarks = new ArrayList<>();
        private final Set<ServerSectionKey> sourceAnchors = new LinkedHashSet<>();
        private int sectionIndex;
        private int voxelIndex;
        private boolean cancelled;
        private boolean complete;
        private boolean dirtyReleased;

        ChunkScanTask(LevelChunk chunk) {
            this.chunk = chunk;
            this.chunkKey = new ChunkKey(chunk.getPos().x(), chunk.getPos().z());
            this.chunkSections = chunk.getSections();
            for (int index = 0; index < chunkSections.length; index++) {
                ServerSectionKey key = new ServerSectionKey(
                        chunkKey.x,
                        chunk.getSectionYFromSectionIndex(index),
                        chunkKey.z
                );
                dirtyMarks.add(key);
                markSectionDirty(key, 1);
            }
        }

        int process(int budget) {
            if (cancelled || complete) {
                return 0;
            }
            int consumed = 0;
            while (sectionIndex < chunkSections.length && consumed < budget) {
                LevelChunkSection section = chunkSections[sectionIndex];
                if (section.hasOnlyAir()) {
                    sectionIndex++;
                    voxelIndex = 0;
                    consumed++;
                    continue;
                }

                int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
                while (voxelIndex < ServerLightSection.VOXEL_COUNT && consumed < budget) {
                    int localX = voxelIndex & 15;
                    int localZ = (voxelIndex >>> 4) & 15;
                    int localY = (voxelIndex >>> 8) & 15;
                    BlockState state = section.getBlockState(localX, localY, localZ);
                    if (state.getLightEmission() > 0) {
                        int worldX = (chunkKey.x << 4) + localX;
                        int worldY = (sectionY << 4) + localY;
                        int worldZ = (chunkKey.z << 4) + localZ;
                        char packed = sourceForState(state);
                        ServerBlockKey sourcePos = new ServerBlockKey(worldX, worldY, worldZ);
                        updateIndexedSource(sourcePos, packed, true);
                        if (packed != 0) {
                            sourceAnchors.add(ServerSectionKey.fromBlock(worldX, worldY, worldZ));
                        }
                    }
                    voxelIndex++;
                    consumed++;
                }
                if (voxelIndex >= ServerLightSection.VOXEL_COUNT) {
                    sectionIndex++;
                    voxelIndex = 0;
                }
            }
            if (sectionIndex >= chunkSections.length) {
                complete = true;
            }
            return consumed;
        }

        void finish() {
            if (cancelled) {
                releaseDirtyMarks();
                return;
            }

            // Source anchors initialize their own 15-block influence volume.
            for (ServerSectionKey anchor : sourceAnchors) {
                scheduleRebuild(anchor);
            }

            // A source in an already-loaded neighboring section may illuminate this new chunk even
            // when this chunk contains no source of its own.
            for (ServerSectionKey key : dirtyMarks) {
                if (hasLitNeighborSection(key)) {
                    scheduleRebuild(key);
                }
            }
            releaseDirtyMarks();
        }

        boolean hasLitNeighborSection(ServerSectionKey key) {
            return nonEmptySection(key.x() - 1, key.y(), key.z())
                    || nonEmptySection(key.x() + 1, key.y(), key.z())
                    || nonEmptySection(key.x(), key.y() - 1, key.z())
                    || nonEmptySection(key.x(), key.y() + 1, key.z())
                    || nonEmptySection(key.x(), key.y(), key.z() - 1)
                    || nonEmptySection(key.x(), key.y(), key.z() + 1);
        }

        boolean nonEmptySection(int x, int y, int z) {
            ServerLightSection section = sections.get(new ServerSectionKey(x, y, z));
            return section != null && !section.isEmpty();
        }

        void releaseDirtyMarks() {
            if (dirtyReleased) {
                return;
            }
            dirtyReleased = true;
            for (ServerSectionKey key : dirtyMarks) {
                markSectionDirty(key, -1);
            }
        }

        void cancel() {
            cancelled = true;
        }

        boolean isCancelled() {
            return cancelled;
        }

        boolean isComplete() {
            return complete;
        }

        ChunkKey chunkKey() {
            return chunkKey;
        }
    }

    private final class LightRebuildTask {
        private static final int CLEAR = 0;
        private static final int SEED_SOURCES = 1;
        private static final int SEED_BOUNDARY = 2;
        private static final int PROPAGATE = 3;
        private static final int DONE = 4;

        private final ServerSectionKey anchor;
        private final LightRebuildRegion region;
        private final List<LightSource> sourceSnapshot;
        private final LongQueue queue = new LongQueue();
        private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        private int phase = CLEAR;
        private long clearCursor;
        private int sourceCursor;
        private long boundaryCursor;

        LightRebuildTask(
                ServerSectionKey anchor,
                LightRebuildRegion region,
                List<LightSource> sourceSnapshot
        ) {
            this.anchor = anchor;
            this.region = region;
            this.sourceSnapshot = sourceSnapshot;
        }

        int process(int budget) {
            int consumed = 0;
            while (consumed < budget && phase != DONE) {
                if (phase == CLEAR) {
                    int used = processClear(budget - consumed);
                    consumed += used;
                    if (clearCursor >= region.volume()) {
                        phase = SEED_SOURCES;
                    }
                    continue;
                }
                if (phase == SEED_SOURCES) {
                    int used = processSources(budget - consumed);
                    consumed += used;
                    if (sourceCursor >= sourceSnapshot.size()) {
                        phase = SEED_BOUNDARY;
                    }
                    continue;
                }
                if (phase == SEED_BOUNDARY) {
                    int used = processBoundary(budget - consumed);
                    consumed += used;
                    if (boundaryCursor >= boundaryCellCount()) {
                        phase = PROPAGATE;
                    }
                    continue;
                }
                if (phase == PROPAGATE) {
                    int used = processPropagation(budget - consumed);
                    consumed += used;
                    if (queue.isEmpty()) {
                        phase = DONE;
                    }
                }
            }
            return consumed;
        }

        private int processClear(int budget) {
            int consumed = 0;
            long volume = region.volume();
            int sizeX = region.sizeX();
            int sizeZ = region.sizeZ();
            long horizontal = (long) sizeX * sizeZ;
            while (clearCursor < volume && consumed < budget) {
                long cursor = clearCursor++;
                int localY = (int) (cursor / horizontal);
                long horizontalIndex = cursor - (long) localY * horizontal;
                int localZ = (int) (horizontalIndex / sizeX);
                int localX = (int) (horizontalIndex - (long) localZ * sizeX);
                int x = region.minX() + localX;
                int y = region.minY() + localY;
                int z = region.minZ() + localZ;
                setPacked(x, y, z, 0);
                consumed++;
            }
            return consumed;
        }

        private int processSources(int budget) {
            int consumed = 0;
            while (sourceCursor < sourceSnapshot.size() && consumed < budget) {
                LightSource source = sourceSnapshot.get(sourceCursor++);
                ServerBlockKey pos = source.position();
                if (loaded(pos.x(), pos.z()) && setPackedMax(pos.x(), pos.y(), pos.z(), source.packedRgb())) {
                    queue.add(PackedServerPos.pack(pos.x(), pos.y(), pos.z()));
                }
                consumed++;
            }
            return consumed;
        }

        private int processBoundary(int budget) {
            int consumed = 0;
            long count = boundaryCellCount();
            while (boundaryCursor < count && consumed < budget) {
                seedBoundaryCell(boundaryCursor++);
                consumed++;
            }
            return consumed;
        }

        private long boundaryCellCount() {
            return 2L * region.sizeY() * region.sizeZ()
                    + 2L * region.sizeX() * region.sizeZ()
                    + 2L * region.sizeX() * region.sizeY();
        }

        private void seedBoundaryCell(long cursor) {
            long segment = (long) region.sizeY() * region.sizeZ();
            if (cursor < segment * 2L) {
                boolean maxFace = cursor >= segment;
                long index = cursor % segment;
                int y = region.minY() + (int) (index / region.sizeZ());
                int z = region.minZ() + (int) (index % region.sizeZ());
                int x = maxFace ? region.maxX() : region.minX();
                seedFromOutside(x, y, z, maxFace ? x + 1 : x - 1, y, z);
                return;
            }
            cursor -= segment * 2L;

            segment = (long) region.sizeX() * region.sizeZ();
            if (cursor < segment * 2L) {
                boolean maxFace = cursor >= segment;
                long index = cursor % segment;
                int x = region.minX() + (int) (index % region.sizeX());
                int z = region.minZ() + (int) (index / region.sizeX());
                int y = maxFace ? region.maxY() : region.minY();
                seedFromOutside(x, y, z, x, maxFace ? y + 1 : y - 1, z);
                return;
            }
            cursor -= segment * 2L;

            segment = (long) region.sizeX() * region.sizeY();
            boolean maxFace = cursor >= segment;
            long index = cursor % segment;
            int x = region.minX() + (int) (index % region.sizeX());
            int y = region.minY() + (int) (index / region.sizeX());
            int z = maxFace ? region.maxZ() : region.minZ();
            seedFromOutside(x, y, z, x, y, maxFace ? z + 1 : z - 1);
        }

        private void seedFromOutside(int x, int y, int z, int outsideX, int outsideY, int outsideZ) {
            if (!level.isInsideBuildHeight(y)
                    || !level.isInsideBuildHeight(outsideY)
                    || !loaded(x, z)
                    || !loaded(outsideX, outsideZ)) {
                return;
            }
            int outside = getPacked(outsideX, outsideY, outsideZ);
            if (outside == 0) {
                return;
            }
            mutablePos.set(x, y, z);
            int incoming = GameplayLightSource.attenuate(outside, level.getBlockState(mutablePos));
            if (incoming != 0 && setPackedMax(x, y, z, incoming)) {
                queue.add(PackedServerPos.pack(x, y, z));
            }
        }

        private int processPropagation(int budget) {
            int consumed = 0;
            while (!queue.isEmpty() && consumed < budget) {
                long packedPos = queue.remove();
                int x = PackedServerPos.x(packedPos);
                int y = PackedServerPos.y(packedPos);
                int z = PackedServerPos.z(packedPos);
                int current = getPacked(x, y, z);
                if (current != 0) {
                    propagateNeighbor(x - 1, y, z, current);
                    propagateNeighbor(x + 1, y, z, current);
                    propagateNeighbor(x, y - 1, z, current);
                    propagateNeighbor(x, y + 1, z, current);
                    propagateNeighbor(x, y, z - 1, current);
                    propagateNeighbor(x, y, z + 1, current);
                }
                consumed++;
            }
            return consumed;
        }

        private void propagateNeighbor(int x, int y, int z, int fromPacked) {
            if (!region.contains(x, y, z) || !level.isInsideBuildHeight(y) || !loaded(x, z)) {
                return;
            }
            mutablePos.set(x, y, z);
            int candidate = GameplayLightSource.attenuate(fromPacked, level.getBlockState(mutablePos));
            if (candidate != 0 && setPackedMax(x, y, z, candidate)) {
                queue.add(PackedServerPos.pack(x, y, z));
            }
        }

        private boolean setPackedMax(int x, int y, int z, int candidate) {
            int previous = getPacked(x, y, z);
            int next = PackedRgbLight.componentMax(previous, candidate);
            return next != previous && setPacked(x, y, z, next);
        }

        boolean isComplete() {
            return phase == DONE;
        }
    }

    private record ChunkKey(int x, int z) {
    }
}
