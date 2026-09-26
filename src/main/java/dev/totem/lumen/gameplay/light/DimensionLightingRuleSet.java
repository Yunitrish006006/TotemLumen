package dev.totem.lumen.gameplay.light;

import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable dimension gameplay-light profile snapshot. */
public final class DimensionLightingRuleSet {
    public static final DimensionLightingRuleSet EMPTY = new DimensionLightingRuleSet(Map.of());

    private final Map<Identifier, DimensionLightingRule> rules;

    private DimensionLightingRuleSet(Map<Identifier, DimensionLightingRule> rules) {
        this.rules = Map.copyOf(new LinkedHashMap<>(rules));
    }

    public static DimensionLightingRuleSet of(Map<Identifier, DimensionLightingRule> rules) {
        return rules.isEmpty() ? EMPTY : new DimensionLightingRuleSet(rules);
    }

    public DimensionLightingRule ruleFor(Identifier dimensionId) {
        return rules.get(dimensionId);
    }

    public int size() {
        return rules.size();
    }

    public Map<Identifier, DimensionLightingRule> rules() {
        return rules;
    }
}
