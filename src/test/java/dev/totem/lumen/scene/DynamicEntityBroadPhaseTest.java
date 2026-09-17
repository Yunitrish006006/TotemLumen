package dev.totem.lumen.scene;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DynamicEntityBroadPhaseTest {
    @Test
    void binsEntityIntoOverlappedSectionsIncludingNegativeCoordinates() {
        DynamicEntitySnapshot entity = snapshot(
                1L,
                "minecraft:overworld",
                -0.5,
                63.0,
                15.5,
                1.0f,
                2.0f,
                1.0f
        );

        DynamicEntityBroadPhase broadPhase = DynamicEntityBroadPhase.build(List.of(entity));

        assertArrayEquals(
                new int[] {0},
                broadPhase.entityIndices(new SectionKey("minecraft:overworld", -1, 3, 0))
        );
        assertArrayEquals(
                new int[] {0},
                broadPhase.entityIndices(new SectionKey("minecraft:overworld", 0, 4, 1))
        );
        assertEquals(8, broadPhase.bucketCount());
        assertEquals(0, broadPhase.overflowAssignments());
    }

    @Test
    void keepsDimensionsSeparated() {
        DynamicEntitySnapshot overworld = snapshot(
                1L,
                "minecraft:overworld",
                1.0,
                1.0,
                1.0,
                1.0f,
                1.0f,
                1.0f
        );
        DynamicEntitySnapshot nether = snapshot(
                2L,
                "minecraft:the_nether",
                1.0,
                1.0,
                1.0,
                1.0f,
                1.0f,
                1.0f
        );

        DynamicEntityBroadPhase broadPhase = DynamicEntityBroadPhase.build(List.of(overworld, nether));

        assertArrayEquals(
                new int[] {0},
                broadPhase.entityIndices(new SectionKey("minecraft:overworld", 0, 0, 0))
        );
        assertArrayEquals(
                new int[] {1},
                broadPhase.entityIndices(new SectionKey("minecraft:the_nether", 0, 0, 0))
        );
    }

    @Test
    void reportsCapacityOverflowExplicitly() {
        DynamicEntitySnapshot first = snapshot(
                1L,
                "minecraft:overworld",
                1.0,
                1.0,
                1.0,
                1.0f,
                1.0f,
                1.0f
        );
        DynamicEntitySnapshot second = snapshot(
                2L,
                "minecraft:overworld",
                2.0,
                1.0,
                1.0,
                1.0f,
                1.0f,
                1.0f
        );

        DynamicEntityBroadPhase broadPhase = DynamicEntityBroadPhase.build(List.of(first, second), 1);

        assertArrayEquals(
                new int[] {0},
                broadPhase.entityIndices(new SectionKey("minecraft:overworld", 0, 0, 0))
        );
        assertEquals(1, broadPhase.overflowAssignments());
    }

    private static DynamicEntitySnapshot snapshot(
            long id,
            String dimension,
            double x,
            double y,
            double z,
            float width,
            float height,
            float depth
    ) {
        float halfWidth = width * 0.5f;
        float halfDepth = depth * 0.5f;
        return new DynamicEntitySnapshot(
                id,
                dimension,
                "minecraft:test_entity",
                x,
                y,
                z,
                new float[] {
                        -halfWidth, 0.0f, -halfDepth,
                         halfWidth, 0.0f, -halfDepth,
                         halfWidth, height, halfDepth,
                        -halfWidth, height, halfDepth
                }
        );
    }
}
