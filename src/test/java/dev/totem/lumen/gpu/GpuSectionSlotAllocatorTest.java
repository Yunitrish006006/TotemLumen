package dev.totem.lumen.gpu;

import dev.totem.lumen.scene.SectionKey;
import dev.totem.lumen.scene.SectionSnapshot;
import dev.totem.lumen.scene.SectionVoxelData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GpuSectionSlotAllocatorTest {
    @Test
    void updatesKeepStableSlotAndRemovalRecyclesIt() {
        GpuSectionSlotAllocator allocator = new GpuSectionSlotAllocator(2);
        SectionKey a = new SectionKey("minecraft:overworld", 0, 0, 0);
        SectionKey b = new SectionKey("minecraft:overworld", 1, 0, 0);

        var first = allocator.apply(snapshot(a, 1, 3));
        assertEquals(GpuSectionSlotAllocator.ChangeKind.ALLOCATED, first.kind());
        assertEquals(0, first.slot());

        var update = allocator.apply(snapshot(a, 2, 4));
        assertEquals(GpuSectionSlotAllocator.ChangeKind.UPDATED, update.kind());
        assertEquals(first.slot(), update.slot());

        var remove = allocator.apply(new SectionSnapshot(a, 3, SectionVoxelData.empty()));
        assertEquals(GpuSectionSlotAllocator.ChangeKind.REMOVED, remove.kind());
        assertEquals(0, allocator.usedSlots());

        var second = allocator.apply(snapshot(b, 1, 7));
        assertEquals(GpuSectionSlotAllocator.ChangeKind.ALLOCATED, second.kind());
        assertEquals(1, second.slot());
    }

    @Test
    void staleRevisionIsIgnored() {
        GpuSectionSlotAllocator allocator = new GpuSectionSlotAllocator(1);
        SectionKey key = new SectionKey("minecraft:overworld", 0, 0, 0);
        allocator.apply(snapshot(key, 5, 3));

        var stale = allocator.apply(snapshot(key, 4, 8));
        assertEquals(GpuSectionSlotAllocator.ChangeKind.IGNORED, stale.kind());
        assertEquals(0, stale.slot());
    }

    @Test
    void capacityFailureIsExplicit() {
        GpuSectionSlotAllocator allocator = new GpuSectionSlotAllocator(1);
        allocator.apply(snapshot(new SectionKey("minecraft:overworld", 0, 0, 0), 1, 1));

        assertThrows(
                GpuSectionSlotAllocator.CapacityExceededException.class,
                () -> allocator.apply(snapshot(new SectionKey("minecraft:overworld", 1, 0, 0), 1, 2))
        );
    }

    @Test
    void removeChunkReleasesAllVerticalSections() {
        GpuSectionSlotAllocator allocator = new GpuSectionSlotAllocator(4);
        allocator.apply(snapshot(new SectionKey("minecraft:overworld", 2, 0, 3), 1, 1));
        allocator.apply(snapshot(new SectionKey("minecraft:overworld", 2, 1, 3), 1, 1));
        allocator.apply(snapshot(new SectionKey("minecraft:overworld", 9, 0, 9), 1, 1));

        assertEquals(2, allocator.removeChunk("minecraft:overworld", 2, 3).size());
        assertEquals(1, allocator.usedSlots());
        assertEquals(3, allocator.freeSlots());
    }

    private static SectionSnapshot snapshot(SectionKey key, long revision, int materialId) {
        int[] ids = new int[SectionVoxelData.VOXEL_COUNT];
        ids[0] = materialId;
        return new SectionSnapshot(key, revision, new SectionVoxelData(ids));
    }
}
