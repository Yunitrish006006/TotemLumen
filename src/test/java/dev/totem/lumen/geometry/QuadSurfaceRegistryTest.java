package dev.totem.lumen.geometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class QuadSurfaceRegistryTest {
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
