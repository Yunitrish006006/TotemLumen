package dev.totem.lumen.world;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class LightingWorldRuleTest {
    @Test
    void validatesNormalizedEmissionColor() {
        assertThrows(IllegalArgumentException.class, () -> new LightingWorldRule(-0.01f, 0.5f, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> new LightingWorldRule(0.5f, 1.01f, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> new LightingWorldRule(0.5f, 0.5f, Float.NaN));
    }

    @Test
    void validatesGameplayStrengthAndFallsBackToBlockStateEmission() {
        LightingWorldRule fallback = new LightingWorldRule(1.0f, 0.5f, 0.25f);
        assertEquals(LightingWorldRule.USE_BLOCK_STATE, fallback.gameplayStrength());
        assertEquals(11, fallback.gameplayStrengthOr(11));

        LightingWorldRule fixed = new LightingWorldRule(1.0f, 0.5f, 0.25f, 7);
        assertEquals(7, fixed.gameplayStrengthOr(11));
        assertThrows(IllegalArgumentException.class, () -> new LightingWorldRule(1.0f, 1.0f, 1.0f, -2));
        assertThrows(IllegalArgumentException.class, () -> new LightingWorldRule(1.0f, 1.0f, 1.0f, 16));
    }

    @Test
    void ruleSetCopiesInputAndUsesEmptySingleton() {
        assertSame(LightingWorldRuleSet.EMPTY, LightingWorldRuleSet.of(Map.of()));

        Identifier torch = Identifier.fromNamespaceAndPath("minecraft", "torch");
        LightingWorldRule rule = new LightingWorldRule(1.0f, 0.55f, 0.22f);
        Map<Identifier, LightingWorldRule> mutable = new LinkedHashMap<>();
        mutable.put(torch, rule);

        LightingWorldRuleSet snapshot = LightingWorldRuleSet.of(mutable);
        mutable.clear();

        assertEquals(1, snapshot.size());
        assertEquals(rule, snapshot.ruleFor(torch));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.rules().clear());
    }
}
