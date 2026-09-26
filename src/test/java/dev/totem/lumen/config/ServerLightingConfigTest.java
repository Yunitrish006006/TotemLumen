package dev.totem.lumen.config;

import dev.totem.lumen.world.EffectiveLightingRules;
import dev.totem.lumen.world.LightingWorldRule;
import dev.totem.lumen.world.LightingWorldRuleSet;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ServerLightingConfigTest {
    @Test
    void defaultsAndOperatorOverrideAreParsed() {
        assertEquals(ServerLightingConfig.DEFAULT,
                ServerLightingConfig.parse(ServerLightingConfig.DEFAULT_JSON));
        ServerLightingConfig configured = ServerLightingConfig.parse("""
                {
                  "schema_version": 1,
                  "gameplay": {
                    "spawn_light_mode": "VANILLA",
                    "work_budget_per_tick": 12000,
                    "time_budget_ms": 1.5
                  },
                  "held_light": { "enabled": false },
                  "block_overrides": {
                    "minecraft:redstone_ore": {
                      "emission_color": [1.0, 0.12, 0.03],
                      "gameplay_strength": 6
                    }
                  }
                }
                """);
        assertEquals(ServerLightingConfig.SpawnMode.VANILLA, configured.spawnMode());
        assertEquals(12_000, configured.workBudget());
        assertEquals(1_500_000L, configured.timeBudgetNanos());
        assertFalse(configured.heldLightEnabled());
        assertEquals(6, configured.blockOverrides().values().iterator().next().gameplayStrength());
    }

    @Test
    void rejectsBadOrAmbiguousSettings() {
        assertThrows(IllegalArgumentException.class,
                () -> ServerLightingConfig.parse(ServerLightingConfig.DEFAULT_JSON.replace("20000", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> ServerLightingConfig.parse(ServerLightingConfig.DEFAULT_JSON.replace("2.0", "9.0")));
        assertThrows(IllegalArgumentException.class,
                () -> ServerLightingConfig.parse(ServerLightingConfig.DEFAULT_JSON.replace(
                        "\"block_overrides\": {}", "\"block_overrides\": {}, \"block_overrides\": {}")));
        assertThrows(IllegalArgumentException.class,
                () -> ServerLightingConfig.parse(ServerLightingConfig.DEFAULT_JSON.replace(
                        "\"block_overrides\": {}", "\"block_overrides\": {\"minecraft:torch\": {\"emission_color\": [1.0, -0.1, 0.0]}}")));
    }

    @Test
    void configOverridesDataPackPerFieldAndPreservesFallback() {
        Identifier torch = Identifier.fromNamespaceAndPath("minecraft", "torch");
        Identifier ore = Identifier.fromNamespaceAndPath("minecraft", "redstone_ore");
        var config = new ServerLightingConfig(ServerLightingConfig.SpawnMode.RGB, 20_000,
                2_000_000L, true, Map.of(
                        torch, new ServerLightingConfig.BlockOverride(null, 7),
                        ore, new ServerLightingConfig.BlockOverride(null, 5)));
        var data = LightingWorldRuleSet.of(Map.of(torch, new LightingWorldRule(0.1f, 0.7f, 0.2f, 11)));
        var composed = EffectiveLightingRules.compose(data, config);
        assertEquals(0.1f, composed.rules().ruleFor(torch).emissionR());
        assertEquals(7, composed.rules().ruleFor(torch).gameplayStrength());
        assertEquals(1.0f, composed.rules().ruleFor(ore).emissionR());
        assertEquals(5, composed.rules().ruleFor(ore).gameplayStrength());
        assertTrue(composed.rules().ruleFor(ore).emissionG() < 0.3f);
        assertEquals(EffectiveLightingRules.Origin.SERVER_CONFIG, composed.origins().get(ore));
    }

    @Test
    void distinguishesDefaultPackFromWorldAndServerOverrides() {
        Identifier torch = Identifier.fromNamespaceAndPath("minecraft", "torch");
        Identifier ore = Identifier.fromNamespaceAndPath("minecraft", "redstone_ore");
        var defaults = LightingWorldRuleSet.of(Map.of(
                torch, new LightingWorldRule(1.0f, 0.64f, 0.34f),
                ore, new LightingWorldRule(1.0f, 0.18f, 0.06f)));
        var snapshot = EffectiveLightingRules.compose(defaults, ServerLightingConfig.DEFAULT, Set.of(torch));
        assertEquals(EffectiveLightingRules.Origin.DEFAULT_DATA_PACK, snapshot.origins().get(torch));
        assertEquals(EffectiveLightingRules.Origin.DATA_PACK, snapshot.origins().get(ore));
    }

    @Test
    void disablingDefaultPackUsesNeutralLightButKeepsWorldRules() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        Identifier torch = Identifier.fromNamespaceAndPath("minecraft", "torch");
        Identifier ore = Identifier.fromNamespaceAndPath("minecraft", "redstone_ore");
        var world = LightingWorldRuleSet.of(Map.of(
                ore, new LightingWorldRule(0.8f, 0.1f, 0.1f)));
        var snapshot = EffectiveLightingRules.compose(world, ServerLightingConfig.DEFAULT, Set.of(), false);
        assertEquals(1.0f, snapshot.rules().ruleFor(torch).emissionR());
        assertEquals(1.0f, snapshot.rules().ruleFor(torch).emissionG());
        assertEquals(1.0f, snapshot.rules().ruleFor(torch).emissionB());
        assertEquals(0.1f, snapshot.rules().ruleFor(ore).emissionG());
        assertEquals(EffectiveLightingRules.Origin.DATA_PACK, snapshot.origins().get(ore));
    }
}
