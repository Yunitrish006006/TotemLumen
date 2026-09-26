package dev.totem.lumen.gameplay.light;

import dev.totem.lumen.world.LightingWorldRule;
import dev.totem.lumen.world.LightingWorldRulesReloadListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/** Shared source and attenuation rules used by both server authority and client prediction. */
public final class GameplayLightSource {
    private GameplayLightSource() {
    }

    public static char packedFor(BlockState state, LightingWorldRule rule) {
        int vanillaEmission = state.getLightEmission();
        if (vanillaEmission <= 0) {
            return 0;
        }
        if (rule != null) {
            return PackedRgbLight.fromNormalized(
                    new EmissionColor(rule.emissionR(), rule.emissionG(), rule.emissionB()),
                    rule.gameplayStrengthOr(vanillaEmission)
            );
        }
        String sourceId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return PackedRgbLight.fromNormalized(
                DefaultEmissionColors.forBlock(sourceId, vanillaEmission),
                vanillaEmission
        );
    }

    public static char attenuate(int packed, BlockState state) {
        return RgbLightAttenuation.attenuate(packed, state,
                LightingWorldRulesReloadListener.currentTuning().attenuationMultiplier());
    }
}
