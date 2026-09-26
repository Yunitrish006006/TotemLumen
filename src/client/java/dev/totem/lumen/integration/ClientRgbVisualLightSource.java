package dev.totem.lumen.integration;

import dev.totem.lumen.gameplay.light.GameplayLightSource;
import dev.totem.lumen.gameplay.light.PackedRgbLight;
import dev.totem.lumen.gameplay.light.RgbLightAttenuation;
import dev.totem.lumen.world.LightingWorldRule;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/** Brightens only Minecraft RGB's visual source field; server gameplay light stays unchanged. */
final class ClientRgbVisualLightSource {
    private ClientRgbVisualLightSource() {
    }

    static char packedFor(BlockState state) {
        String sourceId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        LightingWorldRule rule = ClientLightingWorldRules.ruleFor(sourceId);
        return brighten(GameplayLightSource.packedFor(state, rule));
    }

    static char brighten(int packed) {
        if (packed == 0) return 0;
        return PackedRgbLight.packRgba(
                PackedRgbLight.hueRed(packed),
                PackedRgbLight.hueGreen(packed),
                PackedRgbLight.hueBlue(packed),
                Math.min(PackedRgbLight.MAX_CHANNEL, PackedRgbLight.alpha(packed) + 1)
        );
    }

    /** Slightly steeper visual falloff; gameplay and the source cell keep their own strengths. */
    static char attenuate(int packed, BlockState destination) {
        return RgbLightAttenuation.attenuate(packed, destination,
                ClientLightingWorldRules.tuning().attenuationMultiplier());
    }

    static char attenuateRounded(
            int packed,
            float previousDistance,
            float nextDistance
    ) {
        return RgbLightAttenuation.attenuateRounded(packed, previousDistance, nextDistance,
                ClientLightingWorldRules.tuning().attenuationMultiplier());
    }

    static int attenuationLoss(int alpha, int dampening) {
        return RgbLightAttenuation.attenuationLoss(alpha, dampening);
    }

    static int roundedAttenuationLoss(int alpha, float previousDistance, float nextDistance) {
        return RgbLightAttenuation.roundedAttenuationLoss(alpha, previousDistance, nextDistance);
    }
}
