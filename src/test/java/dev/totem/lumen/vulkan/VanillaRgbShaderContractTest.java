package dev.totem.lumen.vulkan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaRgbShaderContractTest {
    @Test
    void containsCoordinatePackingAndRgbaUnpackContract() {
        String source = VanillaRgbShaderContract.lookupSource();

        assertTrue(source.contains("worldBlock.x & 15"));
        assertTrue(source.contains("(local.y << 8u) | (local.z << 4u) | local.x"));
        assertTrue(source.contains("float((packed >> 4u) & 15u)"));
        assertTrue(source.contains("float((packed >> 12u) & 15u)"));
        assertTrue(source.contains("light.rgb * light.a"));
        assertTrue(source.contains("/ 15.0"));
        assertTrue(VanillaRgbShaderContract.sectionBytes() > 8192);
    }
}
