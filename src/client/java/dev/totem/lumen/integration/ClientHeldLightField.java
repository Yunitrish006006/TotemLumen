package dev.totem.lumen.integration;

import dev.totem.lumen.gameplay.light.PackedRgbLight;
import dev.totem.lumen.gameplay.light.RgbLightAttenuation;
import it.unimi.dsi.fastutil.chars.CharArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;

import java.util.BitSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Collection;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Tick-owned visual light. Terrain workers only read fully published sections. */
public final class ClientHeldLightField {
    private static final Direction[] NEIGHBORS = Direction.values();
    private static ClientLevel level;
    private static final Map<Seed, Flood> sources = new LinkedHashMap<>();
    private static final Map<Long, Set<Flood>> contributors = new LinkedHashMap<>();
    private static final ArrayDeque<Flood> pending = new ArrayDeque<>();
    private static final LongOpenHashSet invalidated = new LongOpenHashSet();
    private static final BlockPos.MutableBlockPos sample = new BlockPos.MutableBlockPos();
    private static float attenuation;
    static volatile Snapshot visible = new Snapshot("", new Long2ObjectOpenHashMap<>());

    record Seed(BlockPos cell, char packed) {}
    record Snapshot(String dimension, Long2ObjectOpenHashMap<char[]> sections) {}

    private ClientHeldLightField() {}

    public static void clear() {
        if (!visible.sections.isEmpty()) {
            var meshes = new LongOpenHashSet();
            for (long key : visible.sections.keySet()) markNeighbors(key, meshes);
            visible = new Snapshot("", new Long2ObjectOpenHashMap<>());
            queueMeshes(meshes);
        }
        level = null;
        sources.clear();
        contributors.clear();
        pending.clear();
        invalidated.clear();
    }

    public static void chunkChanged(ClientLevel world, int chunkX, int chunkZ) {
        if (world != level) return;
        for (Seed seed : sources.keySet()) {
            long key = SectionPos.asLong(seed.cell);
            if (Math.abs(SectionPos.x(key) - chunkX) <= 1 && Math.abs(SectionPos.z(key) - chunkZ) <= 1)
                invalidated.add(key);
        }
    }

    public static void tick(Minecraft client) {
        if (level != client.level) {
            clear();
            level = client.level;
        }
        if (level == null) return;
        boolean authoritative = ClientGameplayLightField.hasServerField(level.dimension().identifier().toString());
        Set<Seed> seeds = new LinkedHashSet<>();
        for (var light : ClientHeldLightState.capture(client, 1.0f)) {
            BlockPos cell = BlockPos.containing(light.x(), light.y(), light.z());
            if (!open(cell)) {
                var holder = level.getEntity(light.entityId());
                if (holder == null) continue;
                cell = BlockPos.containing(holder.getX(), light.y(), holder.getZ());
                if (!open(cell)) {
                    cell = BlockPos.containing(holder.getEyePosition());
                    if (!open(cell)) {
                        cell = holder.blockPosition();
                        if (!open(cell)) continue;
                    }
                }
            }
            char packed = authoritative ? light.packed() : ClientRgbVisualLightSource.brighten(light.packed());
            seeds.add(new Seed(cell, packed));
        }
        var dirty = new Long2ObjectOpenHashMap<BitSet>();
        float tuning = ClientLightingWorldRules.tuning().attenuationMultiplier();
        var retired = new java.util.HashSet<Flood>();
        sources.entrySet().removeIf(entry -> {
            Seed seed = entry.getKey();
            long key = SectionPos.asLong(seed.cell);
            if (tuning == attenuation && !invalidated.contains(key) && seeds.contains(seed)) return false;
            var flood = entry.getValue();
            retired.add(flood);
            flood.occupied.forEach((section, cells) -> {
                dirty.computeIfAbsent(section, unused -> new BitSet()).or(cells);
                var local = contributors.get(section);
                local.remove(flood);
                if (local.isEmpty()) contributors.remove(section);
            });
            return true;
        });
        if (!retired.isEmpty()) pending.removeIf(retired::contains);
        invalidated.clear();
        attenuation = tuning;
        for (var seed : seeds) {
            if (sources.containsKey(seed)) continue;
            var flood = new Flood(java.util.List.of(seed));
            sources.put(seed, flood);

            pending.addLast(flood);
            collectChanges(flood, dirty);
        }
        long deadline = System.nanoTime() + 2_000_000L;
        for (int remaining = 4096; !pending.isEmpty() && remaining > 0 && System.nanoTime() < deadline; remaining -= 128) {
            var flood = pending.removeFirst();
            boolean complete = flood.advance((x, y, z, packed) -> {
                if (!level.isInsideBuildHeight(y) || !level.hasChunkAt(x, z)) return 0;
                var state = level.getBlockState(sample.set(x, y, z));
                return state.getLightDampening() >= 15 ? 0 : RgbLightAttenuation.attenuate(packed, state, tuning);
            }, 128, deadline);
            collectChanges(flood, dirty);
            if (!complete) pending.addLast(flood);
        }
        if (dirty.isEmpty()) return;
        // Never hand terrain workers a section still being filled by a flood.
        var sections = new Long2ObjectOpenHashMap<>(visible.sections);
        var meshes = new LongOpenHashSet();
        for (var entry : dirty.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            var local = contributors.get(key);
            if (local == null) {
                if (sections.remove(key) != null) markNeighbors(key, meshes);
                continue;
            }
            char[] previous = sections.get(key);
            char[] next = previous == null ? new char[4096] : previous.clone();
            // Resolve only touched voxels against sources actually reaching this section.
            var fields = local.stream().map(flood -> flood.sections.get(key)).toList();
            var cells = entry.getValue();
            for (int i = cells.nextSetBit(0); i >= 0; i = cells.nextSetBit(i + 1)) {
                char value = 0;
                for (char[] field : fields) value = PackedRgbLight.componentMax(value, field[i]);
                if (next[i] == value) continue;
                next[i] = value;
                int x = i & 15, y = i >> 8, z = i >> 4 & 15;
                for (int dy = y == 0 ? -1 : 0; dy <= (y == 15 ? 1 : 0); dy++)
                    for (int dz = z == 0 ? -1 : 0; dz <= (z == 15 ? 1 : 0); dz++)
                        for (int dx = x == 0 ? -1 : 0; dx <= (x == 15 ? 1 : 0); dx++)
                            meshes.add(SectionPos.offset(key, dx, dy, dz));
            }
            sections.put(key, next);
        }
        visible = new Snapshot(level.dimension().identifier().toString(), sections);
        queueMeshes(meshes);
    }

    private static void collectChanges(Flood flood, Long2ObjectOpenHashMap<BitSet> dirty) {
        flood.changed.forEach((key, cells) -> {
            contributors.computeIfAbsent(key, unused -> new LinkedHashSet<>()).add(flood);
            dirty.computeIfAbsent(key, unused -> new BitSet()).or(cells);
        });
        flood.changed.clear();
    }

    private static boolean open(BlockPos pos) {
        return level.isInsideBuildHeight(pos.getY()) && level.hasChunkAt(pos)
                && level.getBlockState(pos).getLightDampening() < 15;
    }

    private static void markNeighbors(long key, LongOpenHashSet meshes) {
        for (int dy = -1; dy <= 1; dy++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) meshes.add(SectionPos.offset(key, dx, dy, dz));
    }

    private static void queueMeshes(LongOpenHashSet meshes) {
        String dimension = level.dimension().identifier().toString();
        for (long key : meshes) ClientGameplayLightField.markSectionDirty(dimension,
                SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
    }

    @FunctionalInterface
    interface Transfer { char enter(int x, int y, int z, char packed); }

    static final class Flood {
        final Long2ObjectOpenHashMap<char[]> sections = new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<BitSet> occupied = new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<BitSet> changed = new Long2ObjectOpenHashMap<>();
        private final LongArrayFIFOQueue positions = new LongArrayFIFOQueue();
        private final CharArrayFIFOQueue values = new CharArrayFIFOQueue();

        Flood(Collection<Seed> seeds) {
            for (Seed seed : seeds) offer(seed.cell.getX(), seed.cell.getY(), seed.cell.getZ(), seed.packed);
        }

        boolean advance(Transfer transfer, int budget, long deadline) {
            for (int i = 0; i < budget && !positions.isEmpty(); i++) {
                if ((i & 31) == 0 && System.nanoTime() >= deadline) break;
                long pos = positions.dequeueLong();
                char packed = values.dequeueChar();
                for (Direction direction : NEIGHBORS) {
                    int x = BlockPos.getX(pos) + direction.getStepX();
                    int y = BlockPos.getY(pos) + direction.getStepY();
                    int z = BlockPos.getZ(pos) + direction.getStepZ();
                    char candidate = transfer.enter(x, y, z, packed);
                    if (candidate != 0) offer(x, y, z, candidate);
                }
            }
            return positions.isEmpty();
        }

        private void offer(int x, int y, int z, char packed) {
            long key = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
            char[] section = sections.computeIfAbsent(key, unused -> new char[4096]);
            int index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
            char combined = PackedRgbLight.componentMax(section[index], packed);
            if (combined == section[index]) return;
            section[index] = combined;
            occupied.computeIfAbsent(key, unused -> new BitSet()).set(index);
            changed.computeIfAbsent(key, unused -> new BitSet()).set(index);
            positions.enqueue(BlockPos.asLong(x, y, z));
            values.enqueue(packed);
        }
    }
}
