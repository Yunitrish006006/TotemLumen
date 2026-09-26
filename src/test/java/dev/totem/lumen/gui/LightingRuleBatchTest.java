package dev.totem.lumen.gui;

import com.google.gson.JsonParser;
import dev.totem.lumen.network.ServerLightingViewPackets;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
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

    @Test
    void editingOwnedPackPreservesOtherRulesAndOriginalUntilInstalled() throws Exception {
        Path original = staging.resolve("totem-lumen-lighting-multi-test");
        var torch = new LightingRuleDraft(entry("torch", 3), 240, 128, 64, -1);
        var lantern = new LightingRuleDraft(entry("lantern", 0), 32, 192, 255, 15);
        ClientLightingPackManager.writePack(original, "multi-test", List.of(torch, lantern));
        Path changed = staging.resolve("changed");
        var newTorch = new LightingRuleDraft(entry("torch", 3), 12, 128, 64, -1);
        var newLamp = new LightingRuleDraft(entry("redstone_lamp", 0), 255, 80, 24, 15);

        ClientLightingPackManager.copyPackForEdit(original, changed, "multi-test",
                List.of(newTorch, newLamp));

        Path rules = Path.of("data/minecraft/totem_lumen/lighting");
        assertEquals(240 / 255.0f, JsonParser.parseString(
                Files.readString(original.resolve(rules).resolve("torch.json")))
                .getAsJsonObject().getAsJsonArray("emission_color").get(0).getAsFloat());
        assertEquals(12 / 255.0f, JsonParser.parseString(
                Files.readString(changed.resolve(rules).resolve("torch.json")))
                .getAsJsonObject().getAsJsonArray("emission_color").get(0).getAsFloat());
        assertEquals(Files.readString(original.resolve(rules).resolve("lantern.json")),
                Files.readString(changed.resolve(rules).resolve("lantern.json")));
        assertFalse(Files.exists(original.resolve(rules).resolve("redstone_lamp.json")));
        assertEquals(15, JsonParser.parseString(Files.readString(
                        changed.resolve(rules).resolve("redstone_lamp.json")))
                .getAsJsonObject().get("gameplay_strength").getAsInt());
        assertEquals(Files.readString(original.resolve("pack.mcmeta")),
                Files.readString(changed.resolve("pack.mcmeta")));

        Path backup = staging.resolve("backup");
        ClientLightingPackManager.installEditedPack(original, changed, backup);
        assertEquals(12 / 255.0f, JsonParser.parseString(
                Files.readString(original.resolve(rules).resolve("torch.json")))
                .getAsJsonObject().getAsJsonArray("emission_color").get(0).getAsFloat());
        ClientLightingPackManager.restoreEditedPack(original, backup, staging.resolve("failed"));
        assertEquals(240 / 255.0f, JsonParser.parseString(
                Files.readString(original.resolve(rules).resolve("torch.json")))
                .getAsJsonObject().getAsJsonArray("emission_color").get(0).getAsFloat());
    }

    @Test
    void onlyOwnedPackIdsAndMetadataCanBeUpdated() throws Exception {
        assertTrue(ClientLightingPackManager.canEditExisting("file/totem-lumen-lighting-my-pack"));
        assertTrue(ClientLightingPackManager.canEditExisting("file:totem-lumen-lighting-my-pack"));
        assertFalse(ClientLightingPackManager.canEditExisting("totem-lumen:default_lighting"));
        assertFalse(ClientLightingPackManager.canEditExisting("file/totem-lumen-lighting-../foreign"));

        Path foreign = staging.resolve("foreign");
        var torch = new LightingRuleDraft(entry("torch", 3), 240, 128, 64, -1);
        ClientLightingPackManager.writePack(foreign, "foreign", List.of(torch));
        assertThrows(IOException.class, () -> ClientLightingPackManager.copyPackForEdit(
                foreign, staging.resolve("rejected"), "expected", List.of(torch)));
    }
}
