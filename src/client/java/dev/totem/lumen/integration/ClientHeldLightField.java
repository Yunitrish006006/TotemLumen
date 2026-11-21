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

import java.util.ArrayList;
import java.util.Arrays;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;

/** Tick-owned visual light. Terrain workers only read fully published sections. */
public final class ClientHeldLightField {
    private static final int MAX_LIGHTS = 4;
    private static final int MAX_RADIUS = PackedRgbLight.MAX_CHANNEL;
    private static final Direction[] NEIGHBORS = Direction.values();
    private static ClientLevel level;
    private static List<Seed> lastSeeds = List.of();
    private static Flood pending;
    private static boolean invalidated;
    private static final BlockPos.MutableBlockPos sample = new BlockPos.MutableBlockPos();
    private static float attenuation;
    static volatile Snapshot visible = new Snapshot("", new Long2ObjectOpenHashMap<>());

    record Seed(BlockPos cell, char packed) {}
    record Snapshot(String dimension, Long2ObjectOpenHashMap<char[]> sections) {}

    private ClientHeldLightField() {}

    public static void clear() {
        if (!visible.sections.isEmpty()) publish(new Long2ObjectOpenHashMap<>());
        level = null;
        lastSeeds = List.of();
        pending = null;
        invalidated = false;
    }

    public static void chunkChanged(ClientLevel world, int chunkX, int chunkZ) {
        if (world != level) return;
        for (Seed seed : lastSeeds) {
            if (Math.abs((seed.cell.getX() >> 4) - chunkX) <= 1
                    && Math.abs((seed.cell.getZ() >> 4) - chunkZ) <= 1) {
                invalidated = true;
                return;
            }
        }
    }

    public static void tick(Minecraft client) {
        if (level != client.level) {
            clear();
            level = client.level;
        }
        if (level == null) return;
        var lights = ClientHeldLightState.capture(client, 1.0f);
        if (lights.isEmpty()) {
            clear();
            return;
        }
        String dimension = level.dimension().identifier().toString();
        boolean authoritative = ClientGameplayLightField.hasServerField(dimension);
        List<Seed> seeds = new ArrayList<>(MAX_LIGHTS);
        for (int i = 0; i < Math.min(MAX_LIGHTS, lights.size()); i++) {
            var light = lights.get(i);
            BlockPos cell = BlockPos.containing(light.x(), light.y(), light.z());
            if (!level.isInsideBuildHeight(cell.getY()) || !level.hasChunkAt(cell)
                    || level.getBlockState(cell).getLightDampening() >= 15) continue;
            char packed = authoritative ? light.packed() : ClientRgbVisualLightSource.brighten(light.packed());
            seeds.add(new Seed(cell, packed));
        }
        float tuning = ClientLightingWorldRules.tuning().attenuationMultiplier();
        if (invalidated || attenuation != tuning || seeds.isEmpty()
                || pending != null && !pending.stillNearby(seeds)) {
            pending = null;
            lastSeeds = List.of();
            invalidated = false;
            attenuation = tuning;
        }
        if (seeds.isEmpty()) {
            clear();
            return;
        }
        if (pending == null && !seeds.equals(lastSeeds)) {
            lastSeeds = List.copyOf(seeds);
            pending = new Flood(lastSeeds);
        }
        if (pending == null) return;
        if (pending.advance((x, y, z, packed) -> {
            if (!level.isInsideBuildHeight(y) || !level.hasChunkAt(x, z)) return 0;
            return RgbLightAttenuation.attenuate(packed, level.getBlockState(sample.set(x, y, z)), tuning);
        }, 4096, System.nanoTime() + 2_000_000L)) {
            publish(pending.sections);
            pending = null;
        }
    }

    private static void publish(Long2ObjectOpenHashMap<char[]> sections) {
        String dimension = level.dimension().identifier().toString();
        Snapshot previous = visible;
        visible = new Snapshot(dimension, sections);
        var keys = new LongOpenHashSet(previous.sections.keySet());
        keys.addAll(sections.keySet());
        var dirty = new LongOpenHashSet();
        for (long key : keys) {
            char[] old = previous.sections.get(key);
            char[] next = sections.get(key);
            if (Arrays.equals(old, next)) continue;
            // Interpolated terrain vertices can sample one cell across a section boundary.
            boolean[] neighbors = new boolean[27];
            for (int i = 0; i < 4096; i++) {
                if ((old == null ? 0 : old[i]) == (next == null ? 0 : next[i])) continue;
                int x = i & 15, y = i >> 8, z = (i >> 4) & 15;
                for (int dy = y == 0 ? -1 : 0; dy <= (y == 15 ? 1 : 0); dy++)
                    for (int dz = z == 0 ? -1 : 0; dz <= (z == 15 ? 1 : 0); dz++)
                        for (int dx = x == 0 ? -1 : 0; dx <= (x == 15 ? 1 : 0); dx++)
                            neighbors[(dy + 1) * 9 + (dz + 1) * 3 + dx + 1] = true;
            }
            for (int i = 0; i < neighbors.length; i++) {
                if (neighbors[i]) dirty.add(SectionPos.asLong(SectionPos.x(key) + i % 3 - 1,
                        SectionPos.y(key) + i / 9 - 1, SectionPos.z(key) + i / 3 % 3 - 1));
            }
        }
        for (long key : dirty) ClientGameplayLightField.markSectionDirty(dimension,
                SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
    }

    @FunctionalInterface
    interface Transfer { char enter(int x, int y, int z, char packed); }

    static final class Flood {
        final Long2ObjectOpenHashMap<char[]> sections = new Long2ObjectOpenHashMap<>();
        private final List<Seed> seeds;
        private final LongArrayFIFOQueue positions = new LongArrayFIFOQueue();
        private final CharArrayFIFOQueue values = new CharArrayFIFOQueue();

        Flood(List<Seed> seeds) {
            this.seeds = seeds;
            for (Seed seed : seeds) offer(seed.cell.getX(), seed.cell.getY(), seed.cell.getZ(), seed.packed);
        }

        // Finish an in-flight step while walking; drop work for a removed or distant source.
        boolean stillNearby(List<Seed> current) {
            if (current.size() != seeds.size()) return false;
            for (Seed old : seeds) {
                if (current.stream().noneMatch(s -> s.packed == old.packed
                        && s.cell.distManhattan(old.cell) <= MAX_RADIUS)) return false;
            }
            return true;
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
            positions.enqueue(BlockPos.asLong(x, y, z));
            values.enqueue(packed);
        }
    }
}
