package dev.totem.lumen.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialRegistryTest {
    @Test
    void airIsAlwaysMaterialZero() {
        MaterialRegistry registry = new MaterialRegistry();

        assertEquals(MaterialRegistry.AIR_ID, registry.idFor(MaterialDefinition.AIR));
        assertEquals(MaterialDefinition.AIR, registry.definition(0));
        assertEquals(1, registry.size());
    }

    @Test
    void equalDefinitionsAreDeduplicated() {
        MaterialRegistry registry = new MaterialRegistry();
        MaterialDefinition stoneA = new MaterialDefinition(
                "minecraft:stone", MaterialFlags.OPAQUE, 0, 0.8f, 0.0f, 1.0f, 1.0f
        );
        MaterialDefinition stoneB = new MaterialDefinition(
                "minecraft:stone", MaterialFlags.OPAQUE, 0, 0.8f, 0.0f, 1.0f, 1.0f
        );

        int first = registry.idFor(stoneA);
        int second = registry.idFor(stoneB);

        assertEquals(first, second);
        assertEquals(2, registry.size());
    }

    @Test
    void flagsCanBeCombined() {
        MaterialDefinition lava = new MaterialDefinition(
                "minecraft:lava",
                MaterialFlags.TRANSLUCENT | MaterialFlags.FLUID | MaterialFlags.EMISSIVE,
                15,
                0.1f,
                0.0f,
                0.1f,
                1.4f
        );

        assertTrue(lava.has(MaterialFlags.TRANSLUCENT));
        assertTrue(lava.has(MaterialFlags.FLUID));
        assertTrue(lava.has(MaterialFlags.EMISSIVE));
    }
}
