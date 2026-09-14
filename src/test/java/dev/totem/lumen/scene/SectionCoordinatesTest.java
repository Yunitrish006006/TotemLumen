package dev.totem.lumen.scene;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionCoordinatesTest {
    @Test
    void dirtyHaloInsideSectionTouchesOneSection() {
        Set<SectionKey> keys = new HashSet<>();
        SectionCoordinates.forDirtyHalo("minecraft:overworld", 8, 8, 8, keys::add);

        assertEquals(1, keys.size());
        assertTrue(keys.contains(new SectionKey("minecraft:overworld", 0, 0, 0)));
    }

    @Test
    void dirtyHaloAtPositiveCornerTouchesEightSections() {
        Set<SectionKey> keys = new HashSet<>();
        SectionCoordinates.forDirtyHalo("minecraft:overworld", 16, 32, 16, keys::add);

        assertEquals(8, keys.size());
        assertTrue(keys.contains(new SectionKey("minecraft:overworld", 0, 1, 0)));
        assertTrue(keys.contains(new SectionKey("minecraft:overworld", 1, 2, 1)));
    }

    @Test
    void negativeCoordinatesUseFloorDivision() {
        assertEquals(-1, SectionCoordinates.blockToSection(-1));
        assertEquals(-1, SectionCoordinates.blockToSection(-16));
        assertEquals(-2, SectionCoordinates.blockToSection(-17));
        assertEquals(0, SectionCoordinates.blockToSection(0));
    }
}
