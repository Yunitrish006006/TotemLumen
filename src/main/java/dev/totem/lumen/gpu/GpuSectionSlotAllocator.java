package dev.totem.lumen.gpu;

import dev.totem.lumen.scene.SectionKey;
import dev.totem.lumen.scene.SectionSnapshot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable fixed-size section-slot allocator for the GPU scene.
 *
 * <p>Each populated section owns one 16 KiB voxel slot. Updating a section keeps its slot stable;
 * removing/all-airing it returns the slot to the free list. This lets the GPU lookup table map
 * section coordinates directly to fixed voxel payload slots without moving unrelated sections.</p>
 */
public final class GpuSectionSlotAllocator {
    private final int capacity;
    private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
    private final Map<SectionKey, SlotState> slots = new HashMap<>();

    public GpuSectionSlotAllocator(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        refillFreeSlots();
    }

    public Change apply(SectionSnapshot snapshot) {
        SectionKey key = snapshot.key();
        SlotState current = slots.get(key);

        if (snapshot.voxels().isAllAir()) {
            return remove(key, snapshot.revision());
        }

        if (current != null) {
            if (snapshot.revision() <= current.revision()) {
                return new Change(ChangeKind.IGNORED, key, current.slot(), snapshot.revision());
            }
            slots.put(key, new SlotState(current.slot(), snapshot.revision()));
            return new Change(ChangeKind.UPDATED, key, current.slot(), snapshot.revision());
        }

        Integer slot = freeSlots.pollFirst();
        if (slot == null) {
            throw new CapacityExceededException(capacity);
        }
        slots.put(key, new SlotState(slot, snapshot.revision()));
        return new Change(ChangeKind.ALLOCATED, key, slot, snapshot.revision());
    }

    public Change remove(SectionKey key) {
        SlotState current = slots.get(key);
        return remove(key, current == null ? -1L : current.revision());
    }

    private Change remove(SectionKey key, long revision) {
        SlotState current = slots.remove(key);
        if (current == null) {
            return new Change(ChangeKind.IGNORED, key, -1, revision);
        }
        freeSlots.addLast(current.slot());
        return new Change(ChangeKind.REMOVED, key, current.slot(), revision);
    }

    public List<Change> removeChunk(String dimensionId, int chunkX, int chunkZ) {
        List<SectionKey> matches = slots.keySet().stream()
                .filter(key -> key.dimensionId().equals(dimensionId) && key.x() == chunkX && key.z() == chunkZ)
                .toList();
        List<Change> changes = new ArrayList<>(matches.size());
        for (SectionKey key : matches) {
            changes.add(remove(key));
        }
        return List.copyOf(changes);
    }

    public void clear() {
        slots.clear();
        freeSlots.clear();
        refillFreeSlots();
    }

    private void refillFreeSlots() {
        for (int slot = 0; slot < capacity; slot++) {
            freeSlots.addLast(slot);
        }
    }

    public int slotFor(SectionKey key) {
        SlotState state = slots.get(key);
        return state == null ? -1 : state.slot();
    }

    public int usedSlots() {
        return slots.size();
    }

    public int freeSlots() {
        return freeSlots.size();
    }

    public int capacity() {
        return capacity;
    }

    private record SlotState(int slot, long revision) {
    }

    public enum ChangeKind {
        ALLOCATED,
        UPDATED,
        REMOVED,
        IGNORED
    }

    public record Change(ChangeKind kind, SectionKey key, int slot, long revision) {
    }

    public static final class CapacityExceededException extends IllegalStateException {
        public CapacityExceededException(int capacity) {
            super("GPU section slot capacity exceeded: " + capacity);
        }
    }
}
