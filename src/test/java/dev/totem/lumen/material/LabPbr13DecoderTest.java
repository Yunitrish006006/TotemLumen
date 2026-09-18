package dev.totem.lumen.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class LabPbr13DecoderTest {
    @Test
    void decodesPerceptualSmoothnessAndDielectricF0() {
        var sample = LabPbr13Decoder.decodeSpecular(128, 115, 0, 127);

        float smoothness = 128.0f / 255.0f;
        assertEquals((1.0f - smoothness) * (1.0f - smoothness), sample.roughness(), 0.00001f);
        assertEquals(115.0f / 255.0f, sample.dielectricF0(), 0.00001f);
        assertFalse(sample.metallic());
        assertEquals(0, sample.metalCode());
        assertEquals(127.0f / 254.0f, sample.emission(), 0.00001f);
    }

    @Test
    void identifiesPredefinedAndCustomMetals() {
        var iron = LabPbr13Decoder.decodeSpecular(220, 230, 0, 255);
        var custom = LabPbr13Decoder.decodeSpecular(220, 255, 0, 255);

        assertTrue(iron.metallic());
        assertTrue(LabPbr13Decoder.isHardcodedMetal(iron.metalCode()));
        assertEquals(230, iron.metalCode());
        assertEquals(0.0f, iron.emission(), 0.0f);

        assertTrue(custom.metallic());
        assertTrue(LabPbr13Decoder.isCustomMetal(custom.metalCode()));
        assertEquals(255, custom.metalCode());
    }

    @Test
    void decodesPorosityAndSubsurfaceRanges() {
        var porous = LabPbr13Decoder.decodeSpecular(0, 0, 64, 0);
        var sss = LabPbr13Decoder.decodeSpecular(0, 0, 255, 0);

        assertEquals(1.0f, porous.porosity(), 0.00001f);
        assertEquals(0.0f, porous.subsurface(), 0.00001f);
        assertEquals(0.0f, sss.porosity(), 0.00001f);
        assertEquals(1.0f, sss.subsurface(), 0.00001f);
    }

    @Test
    void convertsDirectXNormalToTotemTangentConvention() {
        var flat = LabPbr13Decoder.decodeNormal(128, 128, 255, 64);
        assertEquals(0.0039216f, flat.normalX(), 0.0001f);
        assertEquals(-0.0039216f, flat.normalY(), 0.0001f);
        assertTrue(flat.normalZ() > 0.999f);
        assertEquals(1.0f, flat.ambientOcclusion(), 0.00001f);
        assertEquals(64.0f / 255.0f, flat.height(), 0.00001f);
    }

    @Test
    void rejectsOutOfRangeChannels() {
        assertThrows(IllegalArgumentException.class, () -> LabPbr13Decoder.decodeSpecular(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> LabPbr13Decoder.decodeNormal(0, 0, 0, 256));
    }
}
