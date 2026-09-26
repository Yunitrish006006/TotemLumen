package dev.totem.lumen.integration;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeldLightMixinRegistrationTest {
    @Test
    void vanillaWorldProfilesRegisterTheHeldLightRenderHook() throws Exception {
        try (var stream = HeldLightMixinRegistrationTest.class.getResourceAsStream(
                "/totem-lumen.client.mixins.json")) {
            assertNotNull(stream);
            var config = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            assertTrue(config.getAsJsonArray("client").asList().stream()
                    .anyMatch(entry -> "VanillaRgbOverlayMixin".equals(entry.getAsString())));
        }
    }
}
