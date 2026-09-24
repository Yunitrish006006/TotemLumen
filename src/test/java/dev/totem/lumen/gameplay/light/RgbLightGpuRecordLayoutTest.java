package dev.totem.lumen.gameplay.light;

import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RgbLightGpuRecordLayoutTest {
    @Test
    void encodesStableLittleEndianSectionRecords() {
        char[] values = new char[RgbLightGpuRecordLayout.VOXEL_COUNT];
        values[0] = PackedRgbLight.packRgba(15, 4, 1, 9);

        var encoded = RgbLightGpuRecordLayout.encode(List.of(
                new RgbLightGpuRecordLayout.Section(-2, 4, 7, values)
        ));

        assertEquals(ByteOrder.LITTLE_ENDIAN, encoded.order());
        assertEquals(1, encoded.getInt());
        assertEquals(-2, encoded.getInt());
        assertEquals(4, encoded.getInt());
        assertEquals(7, encoded.getInt());
        assertEquals(0, encoded.getInt());
        assertEquals(values[0], encoded.getChar());
        assertEquals(9, PackedRgbLight.alpha(values[0]));
        assertEquals(RgbLightGpuRecordLayout.HEADER_BYTES
                        + RgbLightGpuRecordLayout.VOXEL_COUNT * Character.BYTES,
                encoded.limit() - Integer.BYTES);
    }

    @Test
    void rejectsOversizedUploads() {
        var sections = java.util.stream.IntStream.range(0, RgbLightGpuRecordLayout.MAX_SECTIONS + 1)
                .mapToObj(index -> new RgbLightGpuRecordLayout.Section(0, 0, index, new char[RgbLightGpuRecordLayout.VOXEL_COUNT]))
                .toList();

        assertThrows(IllegalArgumentException.class, () -> RgbLightGpuRecordLayout.encode(sections));
    }
}
