package dev.totem.lumen.integration;

import dev.totem.lumen.world.LightingWorldRule;
import dev.totem.lumen.world.LightingWorldRuleSet;

import java.util.LinkedHashMap;
import java.util.Map;

/** Client-side string-keyed view of the currently connected server's lighting world rules. */
public final class ClientLightingWorldRules {
    private static volatile Map<String, LightingWorldRule> rules = Map.of();

    private ClientLightingWorldRules() {
    }

    /**
     * Applies a complete authoritative snapshot. Returns true only when effective rules changed.
     * Identifier strings are materialized once here so section extraction does not parse identifiers
     * for every voxel.
     */
    public static boolean apply(LightingWorldRuleSet ruleSet) {
        Map<String, LightingWorldRule> mutable = new LinkedHashMap<>();
        ruleSet.rules().forEach((blockId, rule) -> mutable.put(blockId.toString(), rule));
        Map<String, LightingWorldRule> next = Map.copyOf(mutable);

        if (next.equals(rules)) {
            return false;
        }
        rules = next;
        return true;
    }

    public static boolean reset() {
        if (rules.isEmpty()) {
            return false;
        }
        rules = Map.of();
        return true;
    }

    public static LightingWorldRule ruleFor(String sourceId) {
        return rules.get(sourceId);
    }

    public static int size() {
        return rules.size();
    }
}
