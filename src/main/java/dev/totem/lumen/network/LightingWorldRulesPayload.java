package dev.totem.lumen.network;

import dev.totem.lumen.world.LightingWorldRule;
import dev.totem.lumen.world.LightingWorldRuleSet;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-to-client snapshot of the current world's authoritative lighting rules. */
public record LightingWorldRulesPayload(LightingWorldRuleSet rules) implements CustomPacketPayload {
    private static final int MAX_RULES = 8192;

    public static final Type<LightingWorldRulesPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("totem-lumen", "lighting_world_rules")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, LightingWorldRulesPayload> CODEC =
            CustomPacketPayload.codec(LightingWorldRulesPayload::write, LightingWorldRulesPayload::new);

    public LightingWorldRulesPayload {
        if (rules == null) {
            throw new IllegalArgumentException("rules must not be null");
        }
        if (rules.size() > MAX_RULES) {
            throw new IllegalArgumentException("too many lighting world rules: " + rules.size());
        }
    }

    private LightingWorldRulesPayload(RegistryFriendlyByteBuf buffer) {
        this(readRules(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        List<Map.Entry<Identifier, LightingWorldRule>> entries = new ArrayList<>(rules.rules().entrySet());
        entries.sort(Comparator.comparing(entry -> entry.getKey().toString()));

        buffer.writeVarInt(entries.size());
        for (Map.Entry<Identifier, LightingWorldRule> entry : entries) {
            buffer.writeIdentifier(entry.getKey());
            LightingWorldRule rule = entry.getValue();
            buffer.writeFloat(rule.emissionR());
            buffer.writeFloat(rule.emissionG());
            buffer.writeFloat(rule.emissionB());
        }
    }

    private static LightingWorldRuleSet readRules(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_RULES) {
            throw new IllegalArgumentException("invalid lighting world rule count: " + count);
        }

        Map<Identifier, LightingWorldRule> rules = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            Identifier blockId = buffer.readIdentifier();
            LightingWorldRule rule = new LightingWorldRule(
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readFloat()
            );
            if (rules.put(blockId, rule) != null) {
                throw new IllegalArgumentException("duplicate lighting world rule for " + blockId);
            }
        }
        return LightingWorldRuleSet.of(rules);
    }

    @Override
    public Type<LightingWorldRulesPayload> type() {
        return TYPE;
    }
}
