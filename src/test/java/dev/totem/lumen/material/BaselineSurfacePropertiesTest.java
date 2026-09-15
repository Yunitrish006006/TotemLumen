package dev.totem.lumen.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class BaselineSurfacePropertiesTest {
    @Test
    void distinguishesReflectiveBaselineClasses() {
        assertEquals(new SurfaceProperties(0.04f, 0.00f), BaselineSurfaceProperties.forBlock("minecraft:water"));
        assertEquals(new SurfaceProperties(0.05f, 0.00f), BaselineSurfaceProperties.forBlock("minecraft:red_stained_glass"));
        assertEquals(new SurfaceProperties(0.22f, 0.95f), BaselineSurfaceProperties.forBlock("minecraft:gold_block"));
        assertEquals(SurfaceProperties.DEFAULT, BaselineSurfaceProperties.forBlock("minecraft:dirt"));
    }

    @Test
    void validatesNormalizedSurfaceValues() {
        assertThrows(IllegalArgumentException.class, () -> new SurfaceProperties(-0.01f, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceProperties(0.5f, 1.01f));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceProperties(Float.NaN, 0.0f));
    }
}
