package dev.totem.lumen.gameplay.light;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Fixed little-endian record layout for the future vanilla block RGB shader buffer. */
public final class RgbLightGpuRecordLayout {
    public static final int VOXEL_COUNT = 16 * 16 * 16;
    public static final int HEADER_BYTES = Integer.BYTES * 4;
    public static final int SECTION_BYTES = HEADER_BYTES + VOXEL_COUNT * Character.BYTES;
    public static final int MAX_SECTIONS = 1024;

    private RgbLightGpuRecordLayout() {
    }

    public static ByteBuffer encode(List<Section> sections) {
        if (sections.size() > MAX_SECTIONS) {
            throw new IllegalArgumentException("RGB GPU section count exceeds " + MAX_SECTIONS);
        }
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + sections.size() * SECTION_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(sections.size());
        for (Section section : sections) {
            if (section.values().length != VOXEL_COUNT) {
                throw new IllegalArgumentException("RGB GPU section must contain 4096 cells");
            }
            buffer.putInt(section.x());
            buffer.putInt(section.y());
            buffer.putInt(section.z());
            buffer.putInt(0);
            for (char value : section.values()) {
                buffer.putChar(value);
            }
        }
        buffer.flip();
        return buffer;
    }

    public record Section(int x, int y, int z, char[] values) {
        public Section {
            values = values.clone();
        }
    }
}
