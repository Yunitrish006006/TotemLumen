package dev.totem.lumen.gpu;

import dev.totem.lumen.scene.FluidGeometrySnapshot;
import dev.totem.lumen.scene.SectionKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FluidSceneSelectorTest {
    @Test
    void excludesDimensionWideFluidCellsOutsideResidentSections() {
        FluidGeometrySnapshot resident = fluid(1, 64, 1, 1);
        FluidGeometrySnapshot staleLoadedElsewhere = fluid(160, 64, 160, 1);

        FluidSceneSelector.Selection selection = FluidSceneSelector.select(
                List.of(resident, staleLoadedElsewhere),
                List.of(new SectionKey("minecraft:overworld", 0, 4, 0)),
                GpuFluidScene.MAX_FLUID_CELLS,
                GpuFluidScene.MAX_FLUID_QUADS
        );

        assertEquals(1, selection.fluids().size());
        assertEquals(1, selection.fluids().getFirst().blockX());
        assertEquals(1, selection.residentCellCount());
        assertEquals(1, selection.residentQuadCount());
        assertFalse(selection.truncated());
    }

    @Test
    void truncatesFartherResidentSectionsInsteadOfFailingTheRenderer() {
        FluidGeometrySnapshot nearest = fluid(1, 64, 1, 1);
        FluidGeometrySnapshot next = fluid(17, 64, 1, 1);
        FluidGeometrySnapshot farthest = fluid(33, 64, 1, 1);

        FluidSceneSelector.Selection selection = FluidSceneSelector.select(
                List.of(farthest, nearest, next),
                List.of(
                        new SectionKey("minecraft:overworld", 0, 4, 0),
                        new SectionKey("minecraft:overworld", 1, 4, 0),
                        new SectionKey("minecraft:overworld", 2, 4, 0)
                ),
                2,
                2
        );

        assertEquals(3, selection.residentCellCount());
        assertEquals(3, selection.residentQuadCount());
        assertEquals(2, selection.fluids().size());
        assertEquals(1, selection.fluids().get(0).blockX());
        assertEquals(17, selection.fluids().get(1).blockX());
        assertEquals(1, selection.droppedCellCount());
        assertEquals(1, selection.droppedQuadCount());
        assertTrue(selection.truncated());
    }

    private static FluidGeometrySnapshot fluid(int x, int y, int z, int quadCount) {
        float[] positions = new float[quadCount * 12];
        float[] uvs = new float[quadCount * 8];
        int[] colors = new int[quadCount];
        boolean[] doubleSided = new boolean[quadCount];
        for (int quad = 0; quad < quadCount; quad++) {
            int base = quad * 12;
            positions[base] = 0.0F;
            positions[base + 1] = 0.875F;
            positions[base + 2] = 0.0F;
            positions[base + 3] = 1.0F;
            positions[base + 4] = 0.875F;
            positions[base + 5] = 0.0F;
            positions[base + 6] = 1.0F;
            positions[base + 7] = 0.875F;
            positions[base + 8] = 1.0F;
            positions[base + 9] = 0.0F;
            positions[base + 10] = 0.875F;
            positions[base + 11] = 1.0F;
            colors[quad] = 0xFF3F76E4;
        }
        return new FluidGeometrySnapshot(
                "minecraft:overworld",
                "minecraft:water",
                x,
                y,
                z,
                true,
                0xFF3F76E4,
                positions,
                uvs,
                colors,
                doubleSided
        );
    }
}
