package dev.totem.lumen.gameplay.light;

import dev.totem.lumen.TotemLumen;
import dev.totem.lumen.world.EffectiveLightingRules;
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
import java.util.Comparator;

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
    private final Map<ChunkKey, ChunkSourceState> chunkSourceStates = new HashMap<>();
    private final Map<ChunkKey, Long> chunkGenerations = new HashMap<>();
    private final Map<ChunkKey, List<SourceSnapshot>> warmSourcesByChunk = new HashMap<>();
    private final Map<ChunkKey, List<SectionSnapshot>> warmSectionsByChunk = new HashMap<>();
    /** Keep the last stable state of unloaded chunks until the world save completes. */
    private final Map<ChunkKey, List<SourceSnapshot>> unloadedSourcesByChunk = new HashMap<>();
    private final Map<ChunkKey, List<SectionSnapshot>> unloadedSectionsByChunk = new HashMap<>();
    private final Set<ServerSectionKey> provisionalWarmSections = new HashSet<>();

    private LightRebuildTask activeRebuild;
    private long fieldRevision;
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

    public long fieldRevision() {
        return fieldRevision;
    }

    /** Returns the current RGB field for every allocated section, used for an initial client sync. */
    public List<SectionSnapshot> snapshotSections() {
        List<SectionSnapshot> result = new ArrayList<>(sections.size());
        for (Map.Entry<ServerSectionKey, ServerLightSection> entry : sections.entrySet()) {
            if (dirtySections.containsKey(entry.getKey())
                    && !provisionalWarmSections.contains(entry.getKey())) {
                continue;
            }
            result.add(snapshot(entry.getKey(), entry.getValue()));
        }
        result.sort(Comparator
                .comparingInt(SectionSnapshot::x)
                .thenComparingInt(SectionSnapshot::y)
                .thenComparingInt(SectionSnapshot::z));
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
            // Core/halo rebuilds are staged off-field. Still publish only sections whose output
            // core is no longer dirty so a client never observes an obsolete revision.
            if (dirtySections.containsKey(key) && !provisionalWarmSections.contains(key)) {
                continue;
            }
            ServerLightSection section = sections.get(key);
            result.add(new SectionSnapshot(
                    key.x(), key.y(), key.z(), section == null ? new char[ServerLightSection.VOXEL_COUNT] : section.copyValues()
            ));
            iterator.remove();
            provisionalWarmSections.remove(key);
        }
        return result;
    }

    private static SectionSnapshot snapshot(ServerSectionKey key, ServerLightSection section) {
        return new SectionSnapshot(key.x(), key.y(), key.z(), section.copyValues());
    }

    public record SectionSnapshot(int x, int y, int z, char[] values) {
        public SectionSnapshot {
            if (values == null || values.length != ServerLightSection.VOXEL_COUNT) {
                throw new IllegalArgumentException("gameplay light section must contain 4096 cells");
            }
            values = values.clone();
        }
    }

    public record SourceSnapshot(int x, int y, int z, char packedRgb) {
    }

    public record WarmState(long revision, List<SourceSnapshot> sources, List<SectionSnapshot> sections) {
        public WarmState {
            if (revision < 0L || sources == null || sections == null) {
                throw new IllegalArgumentException("invalid gameplay-light warm state");
            }
            sources = List.copyOf(sources);
            sections = List.copyOf(sections);
        }
    }

    public WarmState snapshotWarmState() {
        List<SourceSnapshot> sourceSnapshots = new ArrayList<>();
        unloadedSourcesByChunk.values().forEach(sourceSnapshots::addAll);
        for (Map<ServerBlockKey, Character> bucket : sources.values()) {
            for (Map.Entry<ServerBlockKey, Character> entry : bucket.entrySet()) {
                ServerBlockKey pos = entry.getKey();
                sourceSnapshots.add(new SourceSnapshot(
                        pos.x(), pos.y(), pos.z(), entry.getValue()
                ));
            }
        }
        sourceSnapshots.sort(Comparator
                .comparingInt(SourceSnapshot::y)
                .thenComparingInt(SourceSnapshot::z)
                .thenComparingInt(SourceSnapshot::x));
        List<SectionSnapshot> sectionSnapshots = new ArrayList<>();
        unloadedSectionsByChunk.values().forEach(sectionSnapshots::addAll);
        sectionSnapshots.addAll(snapshotSections());
        sectionSnapshots.sort(Comparator
                .comparingInt(SectionSnapshot::x)
                .thenComparingInt(SectionSnapshot::y)
                .thenComparingInt(SectionSnapshot::z));
        return new WarmState(fieldRevision, sourceSnapshots, sectionSnapshots);
    }

    public void installWarmState(WarmState state) {
        warmSourcesByChunk.clear();
        warmSectionsByChunk.clear();
        provisionalWarmSections.clear();
        fieldRevision = Math.max(fieldRevision, state.revision());
        for (SourceSnapshot source : state.sources()) {
            ChunkKey chunk = new ChunkKey(
                    Math.floorDiv(source.x(), 16),
                    Math.floorDiv(source.z(), 16)
            );
            warmSourcesByChunk.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(source);
        }
        for (SectionSnapshot section : state.sections()) {
            ChunkKey chunk = new ChunkKey(section.x(), section.z());
            warmSectionsByChunk.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(section);
        }
    }

    /** Queue a budgeted source scan for a newly loaded chunk. */
    public void onChunkLoaded(LevelChunk chunk) {
        ChunkKey key = new ChunkKey(chunk.getPos().x(), chunk.getPos().z());
        unloadedSourcesByChunk.remove(key);
        unloadedSectionsByChunk.remove(key);
        applyWarmChunk(key);
        bumpChunkGeneration(key);
        chunkSourceStates.put(key, ChunkSourceState.SCANNING);
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
        rememberUnloadedChunk(chunkKey);
        bumpChunkGeneration(chunkKey);
        chunkSourceStates.remove(chunkKey);
        warmSourcesByChunk.remove(chunkKey);
        warmSectionsByChunk.remove(chunkKey);
        provisionalWarmSections.removeIf(key -> key.x() == chunkX && key.z() == chunkZ);

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
        if (!removedSections.isEmpty()) {
            fieldRevision++;
        }
        dirtySections.keySet().removeIf(key -> key.x() == chunkX && key.z() == chunkZ);

        for (ServerSectionKey key : removedSourceSections) {
            scheduleRebuild(key);
        }
    }

    private void rememberUnloadedChunk(ChunkKey chunk) {
        List<SourceSnapshot> sourceSnapshots = new ArrayList<>();
        for (Map.Entry<ServerSectionKey, Map<ServerBlockKey, Character>> entry : sources.entrySet()) {
            ServerSectionKey section = entry.getKey();
            if (section.x() != chunk.x() || section.z() != chunk.z()) {
                continue;
            }
            for (Map.Entry<ServerBlockKey, Character> source : entry.getValue().entrySet()) {
                ServerBlockKey pos = source.getKey();
                sourceSnapshots.add(new SourceSnapshot(pos.x(), pos.y(), pos.z(), source.getValue()));
            }
        }
        if (sourceSnapshots.isEmpty()) {
            unloadedSourcesByChunk.remove(chunk);
        } else {
            unloadedSourcesByChunk.put(chunk, sourceSnapshots);
        }

        List<SectionSnapshot> sectionSnapshots = new ArrayList<>();
        for (Map.Entry<ServerSectionKey, ServerLightSection> entry : sections.entrySet()) {
            ServerSectionKey section = entry.getKey();
            if (section.x() == chunk.x() && section.z() == chunk.z()
                    && (!dirtySections.containsKey(section) || provisionalWarmSections.contains(section))) {
                sectionSnapshots.add(snapshot(section, entry.getValue()));
            }
        }
        if (sectionSnapshots.isEmpty()) {
            unloadedSectionsByChunk.remove(chunk);
        } else {
            unloadedSectionsByChunk.put(chunk, sectionSnapshots);
        }
    }

    /** React to an authoritative server BlockState update without synchronously propagating light. */
    public void onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState) {
        if (!level.isInsideBuildHeight(pos.getY())) {
            return;
        }

        bumpChunkGeneration(new ChunkKey(
                Math.floorDiv(pos.getX(), 16),
                Math.floorDiv(pos.getZ(), 16)
        ));

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
        chunkSourceStates.clear();
        chunkGenerations.clear();
        warmSourcesByChunk.clear();
        warmSectionsByChunk.clear();
        unloadedSourcesByChunk.clear();
        unloadedSectionsByChunk.clear();
        provisionalWarmSections.clear();
        activeRebuild = null;
        fieldRevision = 0L;
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
        chunkSourceStates.put(scan.chunkKey(), ChunkSourceState.READY);
        bumpChunkGeneration(scan.chunkKey());
        scan.finish();
    }

    private void beginNextRebuild() {
        int attempts = pendingAnchors.size();
        while (attempts-- > 0 && !pendingAnchors.isEmpty()) {
            ServerSectionKey anchor = pendingAnchors.removeFirst();
            LightRebuildRegion region = LightRebuildRegion.around(level, anchor);
            if (!chunkSourcesReady(region)) {
                pendingAnchors.addLast(anchor);
                continue;
            }
            pendingAnchorSet.remove(anchor);
            activeRebuild = new LightRebuildTask(
                    anchor,
                    region,
                    snapshotSources(region),
                    captureChunkInputs(region)
            );
            return;
        }
    }

    private void finishActiveRebuild() {
        LightRebuildTask finished = activeRebuild;
        ServerSectionKey anchor = finished.anchor;
        boolean inputsCurrent = finished.inputsCurrent();
        boolean changed = false;
        if (inputsCurrent) {
            changed = finished.publishCore();
            provisionalWarmSections.remove(anchor);
            if (changed) {
                fieldRevision++;
            }
        }
        markSectionDirty(anchor, -1);
        activeRebuild = null;

        boolean explicitRerun = rerunAnchors.remove(anchor);
        if (explicitRerun) {
            if (pendingAnchorSet.add(anchor)) {
                pendingAnchors.addLast(anchor);
                // The rerun acquired its dirty-section reference when it was requested.
            }
        } else if (!inputsCurrent) {
            scheduleCoreRebuild(anchor);
        }
    }

    /**
     * A change in one section can affect cores up to one section away because gameplay RGB has
     * a hard 15-block maximum propagation distance. Queue every affected output core separately;
     * each job reads a 15-block halo but commits only its own 16^3 core.
     */
    private void scheduleRebuild(ServerSectionKey changedAnchor) {
        LightRebuildRegion affected = LightRebuildRegion.around(level, changedAnchor);
        for (int sectionY = affected.minSectionY(); sectionY <= affected.maxSectionY(); sectionY++) {
            for (int sectionZ = affected.minSectionZ(); sectionZ <= affected.maxSectionZ(); sectionZ++) {
                for (int sectionX = affected.minSectionX(); sectionX <= affected.maxSectionX(); sectionX++) {
                    scheduleCoreRebuild(new ServerSectionKey(sectionX, sectionY, sectionZ));
                }
            }
        }
    }

    private void scheduleCoreRebuild(ServerSectionKey anchor) {
        if (!loaded(anchor.minBlockX(), anchor.minBlockZ())) {
            return;
        }
        if (activeRebuild != null && activeRebuild.anchor.equals(anchor)) {
            if (rerunAnchors.add(anchor)) {
                markSectionDirty(anchor, 1);
            }
            return;
        }
        if (pendingAnchorSet.add(anchor)) {
            pendingAnchors.addLast(anchor);
            markSectionDirty(anchor, 1);
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

    private void applyWarmChunk(ChunkKey chunk) {
        List<SourceSnapshot> warmSources = warmSourcesByChunk.remove(chunk);
        if (warmSources != null) {
            for (SourceSnapshot source : warmSources) {
                updateIndexedSource(
                        new ServerBlockKey(source.x(), source.y(), source.z()),
                        source.packedRgb(),
                        true
                );
            }
        }

        List<SectionSnapshot> warmSections = warmSectionsByChunk.remove(chunk);
        if (warmSections == null) {
            return;
        }
        for (SectionSnapshot snapshot : warmSections) {
            ServerSectionKey key = new ServerSectionKey(snapshot.x(), snapshot.y(), snapshot.z());
            ServerLightSection section = new ServerLightSection();
            char[] values = snapshot.values();
            for (int index = 0; index < values.length; index++) {
                char value = values[index];
                if (value == 0) {
                    continue;
                }
                int localX = index & 15;
                int localZ = (index >>> 4) & 15;
                int localY = (index >>> 8) & 15;
                section.set(localX, localY, localZ, value);
            }
            if (!section.isEmpty()) {
                sections.put(key, section);
                provisionalWarmSections.add(key);
                changedSections.add(key);
            }
        }
    }

    private boolean chunkSourcesReady(LightRebuildRegion region) {
        for (int chunkZ = Math.floorDiv(region.minZ(), 16); chunkZ <= Math.floorDiv(region.maxZ(), 16); chunkZ++) {
            for (int chunkX = Math.floorDiv(region.minX(), 16); chunkX <= Math.floorDiv(region.maxX(), 16); chunkX++) {
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

    private Map<ChunkKey, ChunkInputStamp> captureChunkInputs(LightRebuildRegion region) {
        Map<ChunkKey, ChunkInputStamp> result = new HashMap<>();
        for (int chunkZ = Math.floorDiv(region.minZ(), 16); chunkZ <= Math.floorDiv(region.maxZ(), 16); chunkZ++) {
            for (int chunkX = Math.floorDiv(region.minX(), 16); chunkX <= Math.floorDiv(region.maxX(), 16); chunkX++) {
                ChunkKey key = new ChunkKey(chunkX, chunkZ);
                boolean loaded = loaded(chunkX << 4, chunkZ << 4);
                result.put(key, new ChunkInputStamp(
                        chunkGenerations.getOrDefault(key, 0L),
                        loaded,
                        chunkSourceStates.get(key)
                ));
            }
        }
        return result;
    }

    private boolean chunkInputsCurrent(Map<ChunkKey, ChunkInputStamp> snapshot) {
        for (Map.Entry<ChunkKey, ChunkInputStamp> entry : snapshot.entrySet()) {
            ChunkKey key = entry.getKey();
            ChunkInputStamp expected = entry.getValue();
            boolean loaded = loaded(key.x << 4, key.z << 4);
            ChunkInputStamp current = new ChunkInputStamp(
                    chunkGenerations.getOrDefault(key, 0L),
                    loaded,
                    chunkSourceStates.get(key)
            );
            if (!current.equals(expected)) {
                return false;
            }
        }
        return true;
    }

    private void bumpChunkGeneration(ChunkKey key) {
        chunkGenerations.merge(key, 1L, Long::sum);
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
        result.sort(Comparator
                .comparingInt((LightSource source) -> source.position().y())
                .thenComparingInt(source -> source.position().z())
                .thenComparingInt(source -> source.position().x()));
        return result;
    }

    private char sourceForState(BlockState state) {
        var blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return GameplayLightSource.packedFor(
                state,
                EffectiveLightingRules.ruleFor(blockId)
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
        private final Set<ServerBlockKey> observedEmitters = new HashSet<>();
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
                        observedEmitters.add(sourcePos);
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

            // The persisted source index is only a warm hint. Reconcile it against the
            // live chunk snapshot so offline world edits or a cache written before a crash cannot
            // leave phantom emitters in the authoritative source index.
            Set<ServerSectionKey> removedSourceAnchors = new LinkedHashSet<>();
            Iterator<Map.Entry<ServerSectionKey, Map<ServerBlockKey, Character>>> sourceIterator =
                    sources.entrySet().iterator();
            while (sourceIterator.hasNext()) {
                Map.Entry<ServerSectionKey, Map<ServerBlockKey, Character>> entry = sourceIterator.next();
                ServerSectionKey sectionKey = entry.getKey();
                if (sectionKey.x() != chunkKey.x || sectionKey.z() != chunkKey.z) {
                    continue;
                }
                Iterator<Map.Entry<ServerBlockKey, Character>> bucketIterator =
                        entry.getValue().entrySet().iterator();
                while (bucketIterator.hasNext()) {
                    Map.Entry<ServerBlockKey, Character> sourceEntry = bucketIterator.next();
                    if (observedEmitters.contains(sourceEntry.getKey())) {
                        continue;
                    }
                    if (sourceEntry.getValue() != 0) {
                        removedSourceAnchors.add(ServerSectionKey.fromBlock(
                                sourceEntry.getKey().x(),
                                sourceEntry.getKey().y(),
                                sourceEntry.getKey().z()
                        ));
                    }
                    bucketIterator.remove();
                }
                if (entry.getValue().isEmpty()) {
                    sourceIterator.remove();
                }
            }

            // Current and removed source anchors both invalidate every output core in their
            // 15-block influence. This also replaces provisional warm light from stale sources.
            for (ServerSectionKey anchor : sourceAnchors) {
                scheduleRebuild(anchor);
            }
            for (ServerSectionKey anchor : removedSourceAnchors) {
                scheduleRebuild(anchor);
            }

            // A source in an already-loaded neighboring section may illuminate this new chunk even
            // when this chunk contains no source of its own.
            for (ServerSectionKey key : dirtyMarks) {
                if (hasLitNeighborSection(key)) {
                    scheduleCoreRebuild(key);
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
        private static final int SEED_SOURCES = 0;
        private static final int PROPAGATE = 1;
        private static final int DONE = 2;

        private final ServerSectionKey anchor;
        private final LightRebuildRegion region;
        private final List<LightSource> sourceSnapshot;
        private final Map<ChunkKey, ChunkInputStamp> inputSnapshot;
        private final RgbPropagationQueue queue = new RgbPropagationQueue();
        private final Map<ServerSectionKey, char[]> staged = new HashMap<>();
        private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        private int phase = SEED_SOURCES;
        private int sourceCursor;

        LightRebuildTask(
                ServerSectionKey anchor,
                LightRebuildRegion region,
                List<LightSource> sourceSnapshot,
                Map<ChunkKey, ChunkInputStamp> inputSnapshot
        ) {
            this.anchor = anchor;
            this.region = region;
            this.sourceSnapshot = sourceSnapshot;
            this.inputSnapshot = inputSnapshot;
        }

        int process(int budget) {
            int consumed = 0;
            while (consumed < budget && phase != DONE) {
                if (phase == SEED_SOURCES) {
                    consumed += processSources(budget - consumed);
                    if (sourceCursor >= sourceSnapshot.size()) {
                        phase = PROPAGATE;
                    }
                    continue;
                }
                consumed += processPropagation(budget - consumed);
                if (queue.isEmpty()) {
                    phase = DONE;
                }
            }
            return consumed;
        }

        private int processSources(int budget) {
            int consumed = 0;
            while (sourceCursor < sourceSnapshot.size() && consumed < budget) {
                LightSource source = sourceSnapshot.get(sourceCursor++);
                ServerBlockKey pos = source.position();
                if (loaded(pos.x(), pos.z())
                        && setStagedMax(pos.x(), pos.y(), pos.z(), source.packedRgb())) {
                    queue.add(PackedServerPos.pack(pos.x(), pos.y(), pos.z()), source.packedRgb());
                }
                consumed++;
            }
            return consumed;
        }

        private int processPropagation(int budget) {
            int consumed = 0;
            while (!queue.isEmpty() && consumed < budget) {
                long packedPos = queue.remove();
                int x = PackedServerPos.x(packedPos);
                int y = PackedServerPos.y(packedPos);
                int z = PackedServerPos.z(packedPos);
                int current = queue.packed();
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
            if (candidate != 0 && setStagedMax(x, y, z, candidate)) {
                queue.add(PackedServerPos.pack(x, y, z), candidate);
            }
        }

        private int stagedAt(int x, int y, int z) {
            char[] values = staged.get(ServerSectionKey.fromBlock(x, y, z));
            return values == null ? 0 : values[ServerLightSection.index(x, y, z)];
        }

        private boolean setStaged(int x, int y, int z, int packed) {
            ServerSectionKey key = ServerSectionKey.fromBlock(x, y, z);
            char[] values = staged.computeIfAbsent(key, ignored -> new char[ServerLightSection.VOXEL_COUNT]);
            int index = ServerLightSection.index(x, y, z);
            if (values[index] == (char) packed) {
                return false;
            }
            values[index] = (char) packed;
            return true;
        }

        private boolean setStagedMax(int x, int y, int z, int candidate) {
            int previous = stagedAt(x, y, z);
            int next = PackedRgbLight.componentMax(previous, candidate);
            return next != previous && setStaged(x, y, z, next);
        }

        boolean inputsCurrent() {
            return chunkInputsCurrent(inputSnapshot);
        }

        boolean publishCore() {
            boolean changed = false;
            int minY = Math.max(level.getMinY(), anchor.minBlockY());
            int maxY = Math.min(level.getMaxY() - 1, anchor.maxBlockY());
            for (int y = minY; y <= maxY; y++) {
                for (int z = anchor.minBlockZ(); z <= anchor.maxBlockZ(); z++) {
                    for (int x = anchor.minBlockX(); x <= anchor.maxBlockX(); x++) {
                        changed |= setPacked(x, y, z, stagedAt(x, y, z));
                    }
                }
            }
            ServerLightSection section = sections.get(anchor);
            if (section != null && section.isEmpty()) {
                sections.remove(anchor);
            }
            return changed;
        }

        boolean isComplete() {
            return phase == DONE;
        }
    }

    private enum ChunkSourceState {
        SCANNING,
        READY
    }

    private record ChunkInputStamp(long generation, boolean loaded, ChunkSourceState state) {
    }

    private record ChunkKey(int x, int z) {
    }
}
