package dev.totem.lumen.world;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DefaultLightingDataPackTest {
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
            assertEquals(2.0f, tuning.get("brightness_multiplier").getAsFloat());
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
