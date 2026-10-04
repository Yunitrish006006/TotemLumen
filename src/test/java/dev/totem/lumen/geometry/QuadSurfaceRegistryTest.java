package dev.totem.lumen.geometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class QuadSurfaceRegistryTest {
    @Test
    void legacyAndUntintedSurfacesUseNeutralRgb() {
        QuadSurface face = new QuadSurface("test:untinted", 0, 0, 1, 0, 1, 1, 0, 1);
        assertEquals(0xFFFFFF, face.tintRgb());
        assertEquals(0xFFFFFF, QuadSurface.UNTEXTURED.tintRgb());
    }

    @Test
    void tintAlphaDoesNotBecomeOpacityOrChangeSurfaceIdentity() {
        assertEquals(tinted(0x7F80C040), tinted(0xFF80C040));
        assertEquals(0x80C040, tinted(0x7F80C040).tintRgb());
        assertEquals(0, tinted(0).tintRgb()); // black is valid, not absent
    }

    @Test
    void cubeSurfaceSetsKeepBiomeTintsDistinctButDeduplicateEqualTints() {
        int greenId = BlockSurfaceSetRegistry.register(cube(tinted(0x80C040)));
        int yellowId = BlockSurfaceSetRegistry.register(cube(tinted(0xD0C040)));
        assertTrue(greenId > 0 && yellowId > 0);
        assertNotEquals(greenId, yellowId);
        assertEquals(greenId, BlockSurfaceSetRegistry.register(cube(tinted(0xFF80C040))));
        assertEquals(0x80C040, BlockSurfaceSetRegistry.surfaceSet(greenId).positiveY().tintRgb());
    }

    private static QuadSurface tinted(int tint) {
        return new QuadSurface("test:biome_tinted", 0, 0, 1, 0, 1, 1, 0, 1, tint);
    }

    private static BlockSurfaceSetRegistry.CubeSurfaceSet cube(QuadSurface face) {
        return new BlockSurfaceSetRegistry.CubeSurfaceSet(face, face, face, face, face, face, 0.8f, 0);
    }

    @Test
    void quadSurfaceUsesValueEqualityForTextureAndUvs() {
        QuadSurface a = new QuadSurface(
                "minecraft:block/stone",
                0, 0, 1, 0, 1, 1, 0, 1
        );
        QuadSurface b = new QuadSurface(
                "minecraft:block/stone",
                0, 0, 1, 0, 1, 1, 0, 1
        );
        assertEquals(a, b);
        assertTrue(a.textured());
        assertEquals(1.0f, a.u(2));
        assertEquals(1.0f, a.v(2));
    }

    @Test
    void cubeSurfaceSetsDeduplicate() {
        QuadSurface x = new QuadSurface(
                "minecraft:block/test",
                0, 0, 1, 0, 1, 1, 0, 1
        );
        var set = new BlockSurfaceSetRegistry.CubeSurfaceSet(
                x, x, x, x, x, x, 0.8f, 0.0f
        );

        int first = BlockSurfaceSetRegistry.register(set);
        int second = BlockSurfaceSetRegistry.register(set);

        assertTrue(first > 0);
        assertEquals(first, second);
        assertEquals(set, BlockSurfaceSetRegistry.surfaceSet(first));
    }
}
