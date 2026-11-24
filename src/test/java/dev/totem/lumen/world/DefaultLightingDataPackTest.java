package dev.totem.lumen.world;

import com.google.gson.JsonParser;
import dev.totem.lumen.gameplay.light.DefaultEmissionColors;
import dev.totem.lumen.gameplay.light.EmissionColor;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DefaultLightingDataPackTest {
    @Test
    void magmaBlockRuleUsesWarmBrownAtLevelFive() throws Exception {
        String path = "resourcepacks/default_lighting/data/minecraft/totem_lumen/lighting/magma_block.json";
        try (var stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream);
            var rule = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            var rgb = rule.getAsJsonArray("emission_color");
            assertEquals(5, rule.get("gameplay_strength").getAsInt());
            assertEquals(DefaultEmissionColors.forBlock("minecraft:magma_block", 3),
                    new EmissionColor(rgb.get(0).getAsFloat(), rgb.get(1).getAsFloat(), rgb.get(2).getAsFloat()));
        }
    }

    @Test
    void bundledPackContainsMetadataPaletteAndBrightnessTuning() throws Exception {
        String root = "resourcepacks/default_lighting/";
        try (var metadataStream = getClass().getClassLoader().getResourceAsStream(root + "pack.mcmeta")) {
            assertNotNull(metadataStream);
            var metadata = JsonParser.parseReader(new InputStreamReader(metadataStream,
                    StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(121, metadata.getAsJsonObject("pack").get("min_format").getAsInt());
            assertEquals(121, metadata.getAsJsonObject("pack").get("max_format").getAsInt());
        }
        try (var paletteStream = getClass().getClassLoader().getResourceAsStream(
                root + "data/totem-lumen/totem_lumen/default_lighting.json")) {
            assertNotNull(paletteStream);
            var palette = JsonParser.parseReader(new InputStreamReader(paletteStream,
                    StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(3, palette.getAsJsonArray("fallback").size());
            assertTrue(palette.getAsJsonArray("matchers").size() > 10);
        }
        try (var tuningStream = getClass().getClassLoader().getResourceAsStream(
                root + "data/totem-lumen/totem_lumen/light_tuning.json")) {
            assertNotNull(tuningStream);
            var tuning = JsonParser.parseReader(new InputStreamReader(tuningStream,
                    StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(1.0f, tuning.get("brightness_multiplier").getAsFloat());
            assertEquals(1.0f, tuning.get("attenuation_multiplier").getAsFloat());
        }
        try (var spawnStream = getClass().getClassLoader().getResourceAsStream(
                root + "data/totem-lumen/totem_lumen/spawn_light/nether_mobs.json")) {
            assertNotNull(spawnStream);
        }
        try (var tagStream = getClass().getClassLoader().getResourceAsStream(
                root + "data/totem-lumen/tags/entity_type/nether_mobs.json")) {
            assertNotNull(tagStream);
        }
    }
}
