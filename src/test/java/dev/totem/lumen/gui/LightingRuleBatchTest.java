package dev.totem.lumen.gui;

import com.google.gson.JsonParser;
import dev.totem.lumen.network.ServerLightingViewPackets;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LightingRuleBatchTest {
    @TempDir Path staging;

    private static ServerLightingViewPackets.Entry entry(String path, int origin) {
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", path);
        return new ServerLightingViewPackets.Entry(id, id, origin,
                1.0f, 0.5f, 0.25f, 0, 0, 0, -1, true, "");
    }

    @Test
    void unchangedSlidersAreNotPendingAndOverridesCannotBeStaged() {
        var torch = entry("torch", 3);
        assertFalse(new LightingRuleDraft(torch, 255, 128, 64, -1).changed());
        assertTrue(new LightingRuleDraft(torch, 240, 128, 64, -1).changed());
        assertThrows(IllegalArgumentException.class,
                () -> new LightingRuleDraft(entry("redstone_torch", 2), 255, 128, 64, 7));
    }

    @Test
    void batchWritesTwoRulesIntoOnePack() throws Exception {
        var torch = new LightingRuleDraft(entry("torch", 3), 240, 128, 64, -1);
        var lantern = new LightingRuleDraft(entry("lantern", 0), 32, 192, 255, 15);
        ClientLightingPackManager.writePack(staging, "multi-test", List.of(torch, lantern));

        Path rules = staging.resolve("data/minecraft/totem_lumen/lighting");
        var torchJson = JsonParser.parseString(Files.readString(rules.resolve("torch.json")))
                .getAsJsonObject();
        var lanternJson = JsonParser.parseString(Files.readString(rules.resolve("lantern.json")))
                .getAsJsonObject();
        assertEquals(240 / 255.0f, torchJson.getAsJsonArray("emission_color").get(0).getAsFloat());
        assertFalse(torchJson.has("gameplay_strength"));
        assertEquals(15, lanternJson.get("gameplay_strength").getAsInt());
        assertEquals(121, JsonParser.parseString(Files.readString(staging.resolve("pack.mcmeta")))
                .getAsJsonObject().getAsJsonObject("pack").get("max_format").getAsInt());
    }
}
