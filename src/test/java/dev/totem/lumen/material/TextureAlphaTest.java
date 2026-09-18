package dev.totem.lumen.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class TextureAlphaTest {
    @Test
    void classifiesTransparentPartialAndOpaqueTexels() {
        assertEquals(TextureAlpha.AlphaClass.HOLE, TextureAlpha.classify(0x00112233));
        assertEquals(TextureAlpha.AlphaClass.PARTIAL, TextureAlpha.classify(0x80112233));
        assertEquals(TextureAlpha.AlphaClass.OPAQUE, TextureAlpha.classify(0xFF112233));
    }

    @Test
    void zeroAlphaNeverAcceptsAHit() {
        int texel = 0x0000FF00;
        assertTrue(TextureAlpha.isHole(texel));
        assertFalse(TextureAlpha.acceptsOpaqueHit(texel));
        assertEquals(0.0f, TextureAlpha.coverage(texel), 0.0f);
    }

    @Test
    void partialAlphaRetainsCoverageInsteadOfBecomingGlass() {
        int texel = 0x4000FF00;
        assertTrue(TextureAlpha.isPartial(texel));
        assertFalse(TextureAlpha.isHole(texel));
        assertFalse(TextureAlpha.acceptsOpaqueHit(texel));
        assertEquals(64.0f / 255.0f, TextureAlpha.coverage(texel), 0.00001f);
    }
}
