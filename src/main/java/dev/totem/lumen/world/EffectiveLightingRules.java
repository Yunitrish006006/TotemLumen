package dev.totem.lumen.world;

import dev.totem.lumen.config.ServerLightingConfig;
import dev.totem.lumen.config.ServerLightingConfigStore;
import dev.totem.lumen.gameplay.light.EmissionColor;
import dev.totem.lumen.gameplay.light.DefaultEmissionColors;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** One server-owned, immutable composition used for gameplay, held lights and client snapshots. */
public final class EffectiveLightingRules {
    private static volatile Snapshot snapshot = new Snapshot(LightingWorldRuleSet.EMPTY, Map.of(), 0L);

    public enum Origin { DEFAULT_DATA_PACK, DATA_PACK, SERVER_CONFIG }

    public record Snapshot(LightingWorldRuleSet rules, Map<Identifier, Origin> origins, long revision) {
        public Snapshot {
            origins = Map.copyOf(origins);
        }
    }

    private EffectiveLightingRules() {
    }

    public static Snapshot current() {
        return snapshot;
    }

    public static LightingWorldRule ruleFor(Identifier id) {
        return snapshot.rules().ruleFor(id);
    }

    public static Snapshot compose(LightingWorldRuleSet worldRules, ServerLightingConfig config) {
        return compose(worldRules, config, Set.of());
    }

    public static Snapshot compose(LightingWorldRuleSet worldRules, ServerLightingConfig config,
                                   Set<Identifier> defaultIds) {
        return compose(worldRules, config, defaultIds, true);
    }

    public static Snapshot compose(LightingWorldRuleSet worldRules, ServerLightingConfig config,
                                   Set<Identifier> defaultIds, boolean defaultPackActive) {
        Map<Identifier, LightingWorldRule> merged = new HashMap<>(worldRules.rules());
        Map<Identifier, Origin> origins = new HashMap<>();
        merged.keySet().forEach(id -> origins.put(id,
                defaultIds.contains(id) ? Origin.DEFAULT_DATA_PACK : Origin.DATA_PACK));
        if (!defaultPackActive) {
            for (Block block : BuiltInRegistries.BLOCK) {
                boolean emits = block.getStateDefinition().getPossibleStates().stream()
                        .anyMatch(state -> state.getLightEmission() > 0);
                if (emits) {
                    merged.putIfAbsent(BuiltInRegistries.BLOCK.getKey(block),
                            new LightingWorldRule(1.0f, 1.0f, 1.0f));
                }
            }
        }
        for (Map.Entry<Identifier, ServerLightingConfig.BlockOverride> entry
                : config.blockOverrides().entrySet()) {
            Identifier id = entry.getKey();
            ServerLightingConfig.BlockOverride override = entry.getValue();
            LightingWorldRule base = merged.get(id);
            EmissionColor color = override.color();
            if (color == null && base == null) {
                color = defaultPackActive
                        ? DefaultEmissionColors.forBlock(id.toString(), 15)
                        : new EmissionColor(1.0f, 1.0f, 1.0f);
            }
            merged.put(id, new LightingWorldRule(
                    color == null ? base.emissionR() : color.red(),
                    color == null ? base.emissionG() : color.green(),
                    color == null ? base.emissionB() : color.blue(),
                    override.gameplayStrength() == null
                            ? (base == null ? LightingWorldRule.USE_BLOCK_STATE : base.gameplayStrength())
                            : override.gameplayStrength()));
            origins.put(id, Origin.SERVER_CONFIG);
        }
        if (merged.size() > 8_192) throw new IllegalArgumentException("too many effective lighting rules");
        return new Snapshot(LightingWorldRuleSet.of(merged), origins, snapshot.revision() + 1L);
    }

    public static void install(Snapshot next) {
        snapshot = next;
    }

    public static void rebuild() {
        install(compose(LightingWorldRulesReloadListener.currentRules(),
                ServerLightingConfigStore.current(), LightingWorldRulesReloadListener.currentDefaultIds(),
                LightingWorldRulesReloadListener.defaultPackActive()));
    }

    public static void reset() {
        snapshot = new Snapshot(LightingWorldRuleSet.EMPTY, Map.of(), 0L);
    }
}
