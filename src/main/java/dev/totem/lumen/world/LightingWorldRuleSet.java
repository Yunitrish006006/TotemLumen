package dev.totem.lumen.world;

import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable point-in-time server lighting rules suitable for networking and client application. */
public final class LightingWorldRuleSet {
    public static final LightingWorldRuleSet EMPTY = new LightingWorldRuleSet(Map.of());

    private final Map<Identifier, LightingWorldRule> rules;

    private LightingWorldRuleSet(Map<Identifier, LightingWorldRule> rules) {
        this.rules = Map.copyOf(new LinkedHashMap<>(rules));
    }

    public static LightingWorldRuleSet of(Map<Identifier, LightingWorldRule> rules) {
        if (rules.isEmpty()) {
            return EMPTY;
        }
        return new LightingWorldRuleSet(rules);
    }

    public LightingWorldRule ruleFor(Identifier blockId) {
        return rules.get(blockId);
    }

    public int size() {
        return rules.size();
    }

    public Map<Identifier, LightingWorldRule> rules() {
        return rules;
    }
}
