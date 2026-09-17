package dev.totem.lumen.gpu;

import dev.totem.lumen.scene.FluidGeometrySnapshot;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class GpuFluidSceneTest {
    @Test
    void packsBlockLookupAndResolvedQuadData() {
        FluidGeometrySnapshot water = fluid(
                "minecraft:water", -1, 66, 33, true,
                15.0F, 2.75F, 1.0F,
                16.0F, 2.50F, 1.0F,
                16.0F, 2.50F, 2.0F,
                15.0F, 2.75F, 2.0F,
                0xCC3366FF, false
        );
        FluidGeometrySnapshot lava = fluid(
                "minecraft:lava", 4, 70, -9, true,
                4.0F, 6.9F, 7.0F,
                5.0F, 6.9F, 7.0F,
                5.0F, 6.9F, 8.0F,
                4.0F, 6.9F, 8.0F,
                0xFFFF6600, true
        );

        ByteBuffer buffer = ByteBuffer.allocateDirect((int) GpuFluidScene.MAX_STORAGE_BYTES)
                .order(ByteOrder.nativeOrder());
        GpuFluidScene.PackResult result = GpuFluidScene.pack(buffer, 0, List.of(water, lava));

        assertEquals(2, result.cellCount());
        assertEquals(2, result.totalQuads());
        assertEquals(0, GpuFluidScene.packedFluidIndex(buffer, 0, -1, 66, 33));
        assertEquals(1, GpuFluidScene.packedFluidIndex(buffer, 0, 4, 70, -9));
        assertEquals(-1, GpuFluidScene.packedFluidIndex(buffer, 0, 99, 99, 99));

        int firstDescriptor = GpuFluidScene.CELL_DESCRIPTOR_BASE_WORD;
        assertEquals(GpuFluidScene.FLUID_KIND_WATER, word(buffer, firstDescriptor + 3));
        assertEquals("minecraft:water".hashCode(), word(buffer, firstDescriptor + 4));
        assertEquals(0, word(buffer, firstDescriptor + 5));
        assertEquals(1, word(buffer, firstDescriptor + 6));
        assertEquals(GpuFluidScene.CELL_FLAG_FLUID_ONLY, word(buffer, firstDescriptor + 7));

        int firstQuad = GpuFluidScene.QUAD_POOL_BASE_WORD;
        assertEquals(Float.floatToRawIntBits(15.0F), word(buffer, firstQuad));
        assertEquals(0xCC3366FF, word(buffer, firstQuad + 12));
        assertEquals(0, word(buffer, firstQuad + 13));

        int secondQuad = firstQuad + GpuFluidScene.QUAD_WORDS_PER_RECORD;
        assertEquals(0xFFFF6600, word(buffer, secondQuad + 12));
        assertEquals(1, word(buffer, secondQuad + 13));
    }

    @Test
    void waterloggedCellDoesNotSetFluidOnlyFlag() {
        FluidGeometrySnapshot waterlogged = fluid(
                "minecraft:water", 0, 64, 0, false,
                0, 0.875F, 0, 1, 0.875F, 0, 1, 0.875F, 1, 0, 0.875F, 1,
                0xCC3366FF, true
        );
        ByteBuffer buffer = ByteBuffer.allocateDirect((int) GpuFluidScene.MAX_STORAGE_BYTES)
                .order(ByteOrder.nativeOrder());
        GpuFluidScene.pack(buffer, 0, List.of(waterlogged));
        assertEquals(0, word(buffer, GpuFluidScene.CELL_DESCRIPTOR_BASE_WORD + 7));
    }

    @Test
    void lookupHandlesCollisionsWithoutLosingNegativeCoordinates() {
        int[] a = findCollision();
        FluidGeometrySnapshot first = flatWater(a[0], a[1], a[2]);
        FluidGeometrySnapshot second = flatWater(a[3], a[4], a[5]);
        ByteBuffer buffer = ByteBuffer.allocateDirect((int) GpuFluidScene.MAX_STORAGE_BYTES)
                .order(ByteOrder.nativeOrder());

        GpuFluidScene.PackResult result = GpuFluidScene.pack(buffer, 0, List.of(first, second));
        assertEquals(0, GpuFluidScene.packedFluidIndex(buffer, 0, a[0], a[1], a[2]));
        assertEquals(1, GpuFluidScene.packedFluidIndex(buffer, 0, a[3], a[4], a[5]));
        assertEquals(1, result.maxProbe());
    }

    @Test
    void rejectsCellCapacityOverflow() {
        List<FluidGeometrySnapshot> tooManyCells = new ArrayList<>();
        FluidGeometrySnapshot one = flatWater(0, 64, 0);
        for (int i = 0; i <= GpuFluidScene.MAX_FLUID_CELLS; i++) tooManyCells.add(one);
        ByteBuffer buffer = ByteBuffer.allocateDirect((int) GpuFluidScene.MAX_STORAGE_BYTES)
                .order(ByteOrder.nativeOrder());
        assertThrows(IllegalStateException.class, () -> GpuFluidScene.pack(buffer, 0, tooManyCells));
    }

    private static FluidGeometrySnapshot flatWater(int x, int y, int z) {
        int sx = Math.floorDiv(x, 16);
        int sy = Math.floorDiv(y, 16);
        int sz = Math.floorDiv(z, 16);
        float lx = x - sx * 16.0F;
        float ly = y - sy * 16.0F + 0.875F;
        float lz = z - sz * 16.0F;
        return fluid(
                "minecraft:water", x, y, z, true,
                lx, ly, lz,
                lx + 1.0F, ly, lz,
                lx + 1.0F, ly, lz + 1.0F,
                lx, ly, lz + 1.0F,
                0xCC3F76E4, false
        );
    }

    private static FluidGeometrySnapshot fluid(
            String id, int x, int y, int z, boolean fluidOnly,
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            int color, boolean doubleSided
    ) {
        return new FluidGeometrySnapshot(
                "minecraft:overworld", id, x, y, z, fluidOnly,
                new float[]{x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3},
                new float[]{0, 0, 1, 0, 1, 1, 0, 1},
                new int[]{color},
                new boolean[]{doubleSided}
        );
    }

    private static int[] findCollision() {
        int mask = GpuFluidScene.LOOKUP_CAPACITY - 1;
        int x1 = -37;
        int y1 = 4;
        int z1 = 12;
        int target = GpuSectionLookupTable.hash(x1, y1, z1) & mask;
        for (int x2 = -256; x2 <= 256; x2++) {
            for (int z2 = -256; z2 <= 256; z2++) {
                if (x2 == x1 && z2 == z1) continue;
                if ((GpuSectionLookupTable.hash(x2, y1, z2) & mask) == target) {
                    return new int[]{x1, y1, z1, x2, y1, z2};
                }
            }
        }
        throw new AssertionError("Could not find deterministic lookup collision");
    }

    private static int word(ByteBuffer buffer, int wordIndex) {
        return buffer.getInt(wordIndex * Integer.BYTES);
    }
}
