package dev.totem.lumen.gpu;

import dev.totem.lumen.scene.DynamicEntitySnapshot;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpuDynamicEntitySceneTest {
    @Test
    void packsSectionRelativeDescriptorAndCandidates() {
        ByteBuffer buffer = buffer();
        DynamicEntitySnapshot entity = snapshot(
                0x1_0000_0002L,
                "minecraft:overworld",
                32.25,
                64.5,
                -16.75
        );

        GpuDynamicEntityScene.PackResult result = GpuDynamicEntityScene.pack(
                buffer,
                0,
                List.of(entity)
        );

        assertEquals(1, result.entityCount());
        assertEquals(1, result.totalQuads());
        assertEquals(0, result.overflowAssignments());

        int descriptor = GpuDynamicEntityScene.ENTITY_DESCRIPTOR_BASE_WORD;
        assertEquals(2, word(buffer, descriptor));
        assertEquals(1, word(buffer, descriptor + 1));
        assertEquals(2, word(buffer, descriptor + 2));
        assertEquals(4, word(buffer, descriptor + 3));
        assertEquals(-2, word(buffer, descriptor + 4));
        assertEquals(0.25f, floatWord(buffer, descriptor + 5));
        assertEquals(0.5f, floatWord(buffer, descriptor + 6));
        assertEquals(15.25f, floatWord(buffer, descriptor + 7));
        assertEquals(0, word(buffer, descriptor + 14));
        assertEquals(1, word(buffer, descriptor + 15));

        assertEquals(
                0,
                GpuDynamicEntityScene.packedSectionCandidate(buffer, 0, 1, 4, -2, 0)
        );
        assertEquals(
                0,
                GpuDynamicEntityScene.packedSectionCandidate(buffer, 0, 2, 4, -1, 0)
        );
    }

    @Test
    void repackClearsStaleDescriptorAndSectionBucket() {
        ByteBuffer buffer = buffer();
        DynamicEntitySnapshot entity = snapshot(
                3L,
                "minecraft:overworld",
                0.25,
                1.0,
                0.25
        );
        GpuDynamicEntityScene.pack(buffer, 0, List.of(entity));
        assertEquals(
                0,
                GpuDynamicEntityScene.packedSectionCandidate(buffer, 0, 0, 0, 0, 0)
        );

        GpuDynamicEntityScene.PackResult empty = GpuDynamicEntityScene.pack(buffer, 0, List.of());
        assertEquals(0, empty.entityCount());
        assertEquals(-1, GpuDynamicEntityScene.packedSectionCandidate(buffer, 0, 0, 0, 0, 0));
        assertEquals(0, word(buffer, GpuDynamicEntityScene.ENTITY_DESCRIPTOR_BASE_WORD + 15));
    }

    @Test
    void rejectsMixedDimensionsAndEntityOverflow() {
        ByteBuffer buffer = buffer();
        DynamicEntitySnapshot overworld = snapshot(
                1L,
                "minecraft:overworld",
                0.0,
                0.0,
                0.0
        );
        DynamicEntitySnapshot nether = snapshot(
                2L,
                "minecraft:the_nether",
                0.0,
                0.0,
                0.0
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> GpuDynamicEntityScene.pack(buffer, 0, List.of(overworld, nether))
        );

        DynamicEntitySnapshot[] tooMany = new DynamicEntitySnapshot[GpuDynamicEntityScene.MAX_ENTITIES + 1];
        for (int i = 0; i < tooMany.length; i++) {
            tooMany[i] = snapshot(i + 1L, "minecraft:overworld", i * 2.0, 0.0, 0.0);
        }
        IllegalStateException overflow = assertThrows(
                IllegalStateException.class,
                () -> GpuDynamicEntityScene.pack(buffer, 0, List.of(tooMany))
        );
        assertTrue(overflow.getMessage().contains("entity capacity exceeded"));
    }

    private static ByteBuffer buffer() {
        return ByteBuffer.allocate(GpuDynamicEntityScene.MAX_STORAGE_WORDS * Integer.BYTES)
                .order(ByteOrder.nativeOrder());
    }

    private static DynamicEntitySnapshot snapshot(
            long id,
            String dimension,
            double x,
            double y,
            double z
    ) {
        return new DynamicEntitySnapshot(
                id,
                dimension,
                "minecraft:test_entity",
                x,
                y,
                z,
                new float[] {
                        -0.5f, 0.0f, -0.5f,
                         0.5f, 0.0f, -0.5f,
                         0.5f, 1.0f,  0.5f,
                        -0.5f, 1.0f,  0.5f
                }
        );
    }

    private static int word(ByteBuffer buffer, int word) {
        return buffer.getInt(word * Integer.BYTES);
    }

    private static float floatWord(ByteBuffer buffer, int word) {
        return Float.intBitsToFloat(word(buffer, word));
    }
}
