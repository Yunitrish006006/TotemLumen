package dev.totem.lumen.gpu;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GpuSectionLookupTableTest {
    @Test
    void resolvesNegativeCoordinatesAndSlotZero() {
        GpuSectionLookupTable table = new GpuSectionLookupTable(128);
        table.put(-31, -4, 2, 0);
        table.put(17, 9, -22, 63);

        assertEquals(0, table.findSlot(-31, -4, 2));
        assertEquals(63, table.findSlot(17, 9, -22));
        assertEquals(-1, table.findSlot(-31, -4, 3));
    }

    @Test
    void linearProbingResolvesCollisions() {
        GpuSectionLookupTable table = new GpuSectionLookupTable(8);
        int[] first = null;
        int[] second = null;

        outer:
        for (int x1 = -8; x1 <= 8; x1++) {
            for (int z1 = -8; z1 <= 8; z1++) {
                int bucket = GpuSectionLookupTable.hash(x1, 0, z1) & 7;
                for (int x2 = x1; x2 <= 8; x2++) {
                    for (int z2 = -8; z2 <= 8; z2++) {
                        if (x1 == x2 && z1 == z2) continue;
                        if ((GpuSectionLookupTable.hash(x2, 0, z2) & 7) == bucket) {
                            first = new int[]{x1, 0, z1};
                            second = new int[]{x2, 0, z2};
                            break outer;
                        }
                    }
                }
            }
        }

        assertTrue(first != null && second != null);
        assertEquals(0, table.put(first[0], first[1], first[2], 7));
        assertEquals(1, table.put(second[0], second[1], second[2], 11));
        assertEquals(7, table.findSlot(first[0], first[1], first[2]));
        assertEquals(11, table.findSlot(second[0], second[1], second[2]));
        assertEquals(1, table.maxProbe());
    }

    @Test
    void writesGpuAbiAsXyzAndSlotPlusOne() {
        GpuSectionLookupTable table = new GpuSectionLookupTable(8);
        int x = -3;
        int y = 5;
        int z = 12;
        table.put(x, y, z, 0);

        ByteBuffer buffer = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        int baseWord = 4;
        table.writeTo(buffer, baseWord);

        int bucket = GpuSectionLookupTable.hash(x, y, z) & 7;
        int byteOffset = (baseWord + bucket * GpuSectionLookupTable.WORDS_PER_BUCKET) * Integer.BYTES;
        assertEquals(x, buffer.getInt(byteOffset));
        assertEquals(y, buffer.getInt(byteOffset + 4));
        assertEquals(z, buffer.getInt(byteOffset + 8));
        assertEquals(1, buffer.getInt(byteOffset + 12));
    }

    @Test
    void rejectsNonPowerOfTwoCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new GpuSectionLookupTable(96));
    }
}
