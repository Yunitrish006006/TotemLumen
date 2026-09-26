package dev.totem.lumen.world;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class DoubleLightTestPackTest {
    @Test
    void bundledTestPackHasCurrentMetadataAndDoubleTuning() throws Exception {
        String root = "resourcepacks/double_light_test/";
        try (var stream = getClass().getClassLoader().getResourceAsStream(root + "pack.mcmeta")) {
            assertNotNull(stream);
            var pack = JsonParser.parseReader(new InputStreamReader(stream,
                    StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("pack");
            assertEquals(121, pack.get("min_format").getAsInt());
            assertEquals(121, pack.get("max_format").getAsInt());
        }
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                root + "data/totem-lumen/totem_lumen/light_tuning.json")) {
            assertNotNull(stream);
            var tuning = JsonParser.parseReader(new InputStreamReader(stream,
                    StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(2.0f, tuning.get("brightness_multiplier").getAsFloat());
            assertEquals(2.0f, tuning.get("attenuation_multiplier").getAsFloat());
        }
    }

    @Test
    void invalidMultipliersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LightingTuning(Float.NaN, 2.0f));
        assertThrows(IllegalArgumentException.class, () -> new LightingTuning(2.0f, 0.0f));
    }
}
