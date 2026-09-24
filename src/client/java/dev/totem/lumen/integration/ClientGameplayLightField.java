package dev.totem.lumen.integration;

import dev.totem.lumen.network.GameplayLightSectionsPayload;
import dev.totem.lumen.gameplay.light.PackedRgbLight;
import dev.totem.lumen.gameplay.light.RgbLightGpuRecordLayout;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Client-local RGB field used by the vanilla renderer profile. */
public final class ClientGameplayLightField {
    private static final AtomicInteger DIAGNOSTIC_LOGS = new AtomicInteger();
    private static final Map<Key, char[]> SECTIONS = new ConcurrentHashMap<>();
    /** Local prediction is preferred until the matching stable server section arrives. */
    private static final Map<Key, char[]> LOCAL_SECTIONS = new ConcurrentHashMap<>();
    private static volatile String activeDimension = "minecraft:overworld";
    private static long revision;
    private static boolean rebuildRequested;
    private static int quietTicks;
    private static long lastRebuildTick = Long.MIN_VALUE;
    private static String snapshotDimension;
    private static long snapshotRevision = Long.MIN_VALUE;
    private static List<GpuSection> snapshotCache = List.of();
    private static final LinkedHashSet<SectionCoordinate> dirtySections = new LinkedHashSet<>();
    private static final Set<SectionCoordinate> skyAffectedSections = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<SkyNote> lastSkyNote = new ThreadLocal<>();
    private static volatile int skyRegistryEpoch;
    private static boolean immediateUploadRequested;

    private ClientGameplayLightField() {
    }

    public static void setActiveDimension(String dimension) {
        activeDimension = dimension;
    }

    public static synchronized void apply(GameplayLightSectionsPayload payload) {
        String dimension = payload.dimension().toString();
        if (DIAGNOSTIC_LOGS.get() < 4) {
            int logIndex = DIAGNOSTIC_LOGS.getAndIncrement();
            if (logIndex < 4) {
                dev.totem.lumen.TotemLumenClient.LOGGER.info(
                        "RGB field packet: dimension={}, sections={}, fullSync={}",
                        dimension, payload.sections().size(), payload.fullSync()
                );
            }
        }
        if (payload.fullSync()) {
            SECTIONS.keySet().removeIf(key -> key.dimension.equals(dimension));
            LOCAL_SECTIONS.keySet().removeIf(key -> key.dimension.equals(dimension));
        }
        for (GameplayLightSectionsPayload.Section section : payload.sections()) {
            Key key = new Key(dimension, section.x(), section.y(), section.z());
            LOCAL_SECTIONS.remove(key);
            dirtySections.add(new SectionCoordinate(dimension, section.x(), section.y(), section.z()));
            char[] values = section.values();
            boolean empty = true;
            for (char value : values) {
                if (value != 0) {
                    empty = false;
                    break;
                }
            }
            if (empty) {
                SECTIONS.remove(key);
            } else {
                SECTIONS.put(key, values.clone());
            }
        }
        rebuildRequested = true;
        quietTicks = 0;
        revision++;
        invalidateSnapshot();
    }

    public static int packedAt(String dimension, BlockPos pos) {
        return packedAt(dimension, pos.getX(), pos.getY(), pos.getZ());
    }

    public static int packedAtCurrentDimension(BlockPos pos) {
        return packedAtCurrentDimension(pos.getX(), pos.getY(), pos.getZ());
    }

    public static int packedAtCurrentDimension(int x, int y, int z) {
        return packedAt(activeDimension, x, y, z);
    }

    private static int packedAt(String dimension, int x, int y, int z) {
        Key key = new Key(
                dimension,
                Math.floorDiv(x, 16),
                Math.floorDiv(y, 16),
                Math.floorDiv(z, 16)
        );
        char[] values = LOCAL_SECTIONS.get(key);
        if (values == null) {
            return 0;
        }
        return values[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
    }

    public static synchronized void clear() {
        SECTIONS.clear();
        LOCAL_SECTIONS.clear();
        activeDimension = "minecraft:overworld";
        dirtySections.clear();
        clearSkyAffected();
        rebuildRequested = true;
        quietTicks = 0;
        lastRebuildTick = Long.MIN_VALUE;
        immediateUploadRequested = false;
        revision++;
        invalidateSnapshot();
    }

    public static synchronized void clearDimension(String dimension) {
        SECTIONS.keySet().removeIf(key -> key.dimension.equals(dimension));
        LOCAL_SECTIONS.keySet().removeIf(key -> key.dimension.equals(dimension));
        dirtySections.removeIf(section -> section.dimension.equals(dimension));
        skyAffectedSections.removeIf(section -> section.dimension.equals(dimension));
        skyRegistryEpoch++;
        rebuildRequested = true;
        quietTicks = 0;
        revision++;
        invalidateSnapshot();
    }

    public static synchronized long revision() {
        return revision;
    }

    /** Requests one coalesced vanilla chunk-mesh rebuild after the next RGB update settles. */
    public static synchronized void requestRebuild() {
        rebuildRequested = true;
        quietTicks = 0;
    }

    /** Requests one next-frame overlay upload for a user-visible source change. */
    public static synchronized void requestImmediateUpload() {
        immediateUploadRequested = true;
    }

    public static synchronized boolean consumeImmediateUploadRequest() {
        boolean requested = immediateUploadRequested;
        immediateUploadRequested = false;
        return requested;
    }

    public static synchronized boolean consumeRebuildRequest(long currentTick) {
        if (!rebuildRequested) {
            return false;
        }
        // invalidateCompiledGeometry rebuilds the complete vanilla section set. RGB updates can
        // arrive in many packets while the server is converging, so coalesce them into a bounded
        // refresh cadence instead of repeatedly stalling the render thread during world entry.
        if (lastRebuildTick != Long.MIN_VALUE && currentTick - lastRebuildTick < 40L) {
            return false;
        }
        if (++quietTicks < 3) {
            return false;
        }
        rebuildRequested = false;
        quietTicks = 0;
        lastRebuildTick = currentTick;
        return true;
    }

    /** Drains RGB sections whose vanilla chunk meshes must be re-extracted. */
    public static synchronized List<SectionCoordinate> drainDirtySections(String dimension) {
        return drainDirtySections(dimension, Integer.MAX_VALUE);
    }

    /** Drains a bounded number of dirty sections so propagation cannot monopolize a frame. */
    public static synchronized List<SectionCoordinate> drainDirtySections(String dimension, int limit) {
        List<SectionCoordinate> result = new ArrayList<>();
        var iterator = dirtySections.iterator();
        while (iterator.hasNext() && result.size() < limit) {
            SectionCoordinate section = iterator.next();
            if (section.dimension.equals(dimension)) {
                result.add(section);
                iterator.remove();
            }
        }
        return result;
    }

    /** Queues a bounded mesh refresh when changing profiles, including sections without RGB sources. */
    public static synchronized void markSectionDirty(String dimension, int x, int y, int z) {
        dirtySections.add(new SectionCoordinate(dimension, x, y, z));
    }

    /** Worker-safe registration of meshes whose vertex colors contain baked sky RGB. */
    public static void noteSkyLitSurface(BlockPos pos) {
        String dimension = activeDimension;
        int x = Math.floorDiv(pos.getX(), 16);
        int y = Math.floorDiv(pos.getY(), 16);
        int z = Math.floorDiv(pos.getZ(), 16);
        int epoch = skyRegistryEpoch;
        SkyNote previous = lastSkyNote.get();
        if (previous != null && previous.epoch == epoch
                && previous.section.dimension.equals(dimension)
                && previous.section.x == x && previous.section.y == y
                && previous.section.z == z) {
            return;
        }
        SectionCoordinate section = new SectionCoordinate(dimension, x, y, z);
        lastSkyNote.set(new SkyNote(epoch, section));
        skyAffectedSections.add(section);
    }

    public static List<SectionCoordinate> skyAffectedSections(String dimension) {
        return skyAffectedSections.stream()
                .filter(section -> section.dimension.equals(dimension))
                .toList();
    }

    public static void clearSkyAffected() {
        skyAffectedSections.clear();
        skyRegistryEpoch++;
    }

    public static int colorAt(String dimension, BlockPos pos) {
        return packedAt(dimension, pos);
    }

    /** Immutable upload snapshot for the future vanilla block RenderPipeline RGB buffer. */
    public static synchronized List<GpuSection> snapshotForGpu(String dimension) {
        if (dimension.equals(snapshotDimension) && snapshotRevision == revision) {
            return snapshotCache;
        }
        Map<Key, char[]> merged = new java.util.HashMap<>();
        for (Map.Entry<Key, char[]> entry : SECTIONS.entrySet()) {
            if (entry.getKey().dimension.equals(dimension)) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<Key, char[]> entry : LOCAL_SECTIONS.entrySet()) {
            if (entry.getKey().dimension.equals(dimension)) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        List<GpuSection> result = new ArrayList<>();
        for (Map.Entry<Key, char[]> entry : merged.entrySet()) {
            Key key = entry.getKey();
            result.add(new GpuSection(key.x, key.y, key.z, entry.getValue().clone()));
        }
        result.sort(Comparator
                .comparingInt(GpuSection::x)
                .thenComparingInt(GpuSection::y)
                .thenComparingInt(GpuSection::z));
        snapshotDimension = dimension;
        snapshotRevision = revision;
        snapshotCache = List.copyOf(result);
        return snapshotCache;
    }

    public static synchronized java.nio.ByteBuffer encodeGpuSnapshot(String dimension) {
        return RgbLightGpuRecordLayout.encode(snapshotForGpu(dimension).stream()
                .map(section -> new RgbLightGpuRecordLayout.Section(
                        section.x(), section.y(), section.z(), section.values()
                ))
                .toList());
    }

    public record GpuSection(int x, int y, int z, char[] values) {
        public GpuSection {
            if (values == null || values.length != GameplayLightSectionsPayload.VOXEL_COUNT) {
                throw new IllegalArgumentException("RGB GPU section must contain 4096 cells");
            }
            values = values.clone();
        }
    }

    public record SectionCoordinate(String dimension, int x, int y, int z) {
    }

    /** Writes one predicted cell without blocking the client tick on a full GPU snapshot rebuild. */
    public static synchronized boolean setLocalPacked(String dimension, int x, int y, int z, int packed) {
        Key key = new Key(dimension, Math.floorDiv(x, 16), Math.floorDiv(y, 16), Math.floorDiv(z, 16));
        char[] values = LOCAL_SECTIONS.get(key);
        if (packed == 0) {
            if (values == null) {
                return false;
            }
            int index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
            if (values[index] == 0) {
                return false;
            }
            values[index] = 0;
            boolean any = false;
            for (char value : values) {
                if (value != 0) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                // There is no server-field fallback. Release empty sections instead of keeping
                // a permanent 8 KiB mask for every light that has been removed.
                LOCAL_SECTIONS.remove(key);
            }
            markAffectedMeshes(dimension, x, y, z);
            return true;
        }
        if (values == null) {
            values = new char[GameplayLightSectionsPayload.VOXEL_COUNT];
            LOCAL_SECTIONS.put(key, values);
        }
        int index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        if (values[index] == (char) packed) {
            return false;
        }
        values[index] = (char) packed;
        markAffectedMeshes(dimension, x, y, z);
        return true;
    }

    /** A vertex on a section edge can interpolate RGB cells from the adjacent section. */
    private static void markAffectedMeshes(String dimension, int x, int y, int z) {
        int sectionX = Math.floorDiv(x, 16);
        int sectionY = Math.floorDiv(y, 16);
        int sectionZ = Math.floorDiv(z, 16);
        int edgeX = (x & 15) == 0 ? -1 : (x & 15) == 15 ? 1 : 0;
        int edgeY = (y & 15) == 0 ? -1 : (y & 15) == 15 ? 1 : 0;
        int edgeZ = (z & 15) == 0 ? -1 : (z & 15) == 15 ? 1 : 0;
        for (int dy = Math.min(0, edgeY); dy <= Math.max(0, edgeY); dy++) {
            for (int dz = Math.min(0, edgeZ); dz <= Math.max(0, edgeZ); dz++) {
                for (int dx = Math.min(0, edgeX); dx <= Math.max(0, edgeX); dx++) {
                    dirtySections.add(new SectionCoordinate(
                            dimension, sectionX + dx, sectionY + dy, sectionZ + dz
                    ));
                }
            }
        }
    }

    /** Publish one finished correction, never its temporary empty or partly propagated field. */
    public static synchronized boolean replaceLocalRegion(
            String dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            Map<SectionCoordinate, char[]> staged
    ) {
        boolean changed = false;
        for (int sectionY = Math.floorDiv(minY, 16); sectionY <= Math.floorDiv(maxY, 16); sectionY++) {
            for (int sectionZ = Math.floorDiv(minZ, 16); sectionZ <= Math.floorDiv(maxZ, 16); sectionZ++) {
                for (int sectionX = Math.floorDiv(minX, 16); sectionX <= Math.floorDiv(maxX, 16); sectionX++) {
                    Key key = new Key(dimension, sectionX, sectionY, sectionZ);
                    char[] previous = LOCAL_SECTIONS.get(key);
                    char[] replacement = staged.get(new SectionCoordinate(dimension, sectionX, sectionY, sectionZ));
                    if (previous == null && replacement == null) continue;
                    char[] next = previous == null ? new char[GameplayLightSectionsPayload.VOXEL_COUNT] : previous.clone();
                    boolean sectionChanged = false;
                    for (int y = Math.max(minY, sectionY * 16); y <= Math.min(maxY, sectionY * 16 + 15); y++) {
                        for (int z = Math.max(minZ, sectionZ * 16); z <= Math.min(maxZ, sectionZ * 16 + 15); z++) {
                            for (int x = Math.max(minX, sectionX * 16); x <= Math.min(maxX, sectionX * 16 + 15); x++) {
                                int index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
                                char packed = replacement == null ? 0 : replacement[index];
                                if (next[index] != packed) {
                                    next[index] = packed;
                                    markAffectedMeshes(dimension, x, y, z);
                                    sectionChanged = true;
                                }
                            }
                        }
                    }
                    if (!sectionChanged) continue;
                    boolean any = false;
                    for (char value : next) {
                        if (value != 0) { any = true; break; }
                    }
                    if (any) LOCAL_SECTIONS.put(key, next);
                    else LOCAL_SECTIONS.remove(key);
                    changed = true;
                }
            }
        }
        finishLocalBatch(changed);
        return changed;
    }

    public static synchronized int localPackedAt(String dimension, int x, int y, int z) {
        char[] values = LOCAL_SECTIONS.get(new Key(
                dimension, Math.floorDiv(x, 16), Math.floorDiv(y, 16), Math.floorDiv(z, 16)
        ));
        return values == null ? 0 : values[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
    }

    public static synchronized int serverPackedAt(String dimension, int x, int y, int z) {
        char[] values = SECTIONS.get(new Key(
                dimension, Math.floorDiv(x, 16), Math.floorDiv(y, 16), Math.floorDiv(z, 16)
        ));
        return values == null ? 0 : values[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
    }

    public static synchronized void clearLocal() {
        if (LOCAL_SECTIONS.isEmpty()) {
            return;
        }
        for (Key key : LOCAL_SECTIONS.keySet()) {
            dirtySections.add(new SectionCoordinate(key.dimension, key.x, key.y, key.z));
        }
        LOCAL_SECTIONS.clear();
        revision++;
        invalidateSnapshot();
    }

    /** Discard only the unloaded chunk's cached prediction, leaving distant RGB light intact. */
    public static synchronized void clearLocalChunk(String dimension, int chunkX, int chunkZ) {
        boolean changed = false;
        for (Key key : List.copyOf(LOCAL_SECTIONS.keySet())) {
            if (key.dimension.equals(dimension) && key.x == chunkX && key.z == chunkZ) {
                LOCAL_SECTIONS.remove(key);
                dirtySections.add(new SectionCoordinate(dimension, key.x, key.y, key.z));
                changed = true;
            }
        }
        finishLocalBatch(changed);
        skyAffectedSections.removeIf(section -> section.dimension.equals(dimension)
                && section.x == chunkX && section.z == chunkZ);
        skyRegistryEpoch++;
    }

    public static synchronized void finishLocalBatch(boolean changed) {
        if (changed) {
            revision++;
            invalidateSnapshot();
        }
    }

    private static void invalidateSnapshot() {
        snapshotDimension = null;
        snapshotRevision = Long.MIN_VALUE;
        snapshotCache = List.of();
    }

    private record Key(String dimension, int x, int y, int z) {
    }

    private record SkyNote(int epoch, SectionCoordinate section) {
    }
}
