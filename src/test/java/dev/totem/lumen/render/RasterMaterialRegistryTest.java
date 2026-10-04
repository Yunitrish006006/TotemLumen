package dev.totem.lumen.render;

import dev.totem.lumen.material.MaterialDefinition;
import dev.totem.lumen.material.MaterialFlags;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RasterMaterialRegistryTest {
    private static MaterialDefinition material(String id, float roughness, float metallic) {
        return new MaterialDefinition(
                id,
                MaterialFlags.OPAQUE,
                0,
                0, 0, 0,
                roughness,
                metallic,
                1,
                1,
                1, 1, 1,
                1, 1, 1,
                0
        );
    }

    @Test
    void airAndUnknownReserveZero() {
        var registry = new RasterMaterialRegistry();
        assertEquals(0, registry.register(MaterialDefinition.AIR));
        assertEquals(MaterialDefinition.AIR, registry.material(0));
        assertEquals(0, registry.size());
        assertEquals(0, registry.revision());
    }

    @Test
    void identicalDefinitionsDeduplicateAndChangedDefinitionsGetNewIds() {
        var registry = new RasterMaterialRegistry();
        var stone = material("minecraft:stone", 0.8f, 0);
        int first = registry.register(stone);
        assertTrue(first > 0);
        assertEquals(first, registry.register(stone));
        assertEquals(first, registry.register(material("minecraft:stone", 0.8f, 0)));
        int changed = registry.register(material("minecraft:stone", 0.6f, 0));
        assertNotEquals(first, changed);
        assertEquals(2, registry.size());
        assertEquals(2, registry.revision());
    }

    @Test
    void snapshotIsOrderedImmutableAndKeepsDefinitions() {
        var registry = new RasterMaterialRegistry();
        int a = registry.register(material("minecraft:stone", 0.8f, 0));
        int b = registry.register(material("minecraft:iron_block", 0.2f, 1));
        var snapshot = registry.snapshot();
        assertEquals(2, snapshot.entries().size());
        assertEquals(a, snapshot.entries().get(0).id());
        assertEquals(b, snapshot.entries().get(1).id());
        assertEquals("minecraft:stone", snapshot.entries().get(0).material().sourceId());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.entries().clear());
    }
}
