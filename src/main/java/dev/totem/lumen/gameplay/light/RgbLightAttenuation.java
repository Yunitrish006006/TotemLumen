package dev.totem.lumen.gameplay.light;

import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** Shared radial attenuation math used by the authoritative server field and RGB client field. */
public final class RgbLightAttenuation {
    public static final float RADIAL_DISTANCE_SCALE = 1.20f;
    public static final List<Step> ROUND_STEPS = List.of(
            new Step(-1, 0, 0, 1.0f), new Step(1, 0, 0, 1.0f),
            new Step(0, -1, 0, 1.0f), new Step(0, 1, 0, 1.0f),
            new Step(0, 0, -1, 1.0f), new Step(0, 0, 1, 1.0f),
            new Step(-1, -1, 0, 1.4142135f), new Step(-1, 1, 0, 1.4142135f),
            new Step(1, -1, 0, 1.4142135f), new Step(1, 1, 0, 1.4142135f),
            new Step(-1, 0, -1, 1.4142135f), new Step(-1, 0, 1, 1.4142135f),
            new Step(1, 0, -1, 1.4142135f), new Step(1, 0, 1, 1.4142135f),
            new Step(0, -1, -1, 1.4142135f), new Step(0, -1, 1, 1.4142135f),
            new Step(0, 1, -1, 1.4142135f), new Step(0, 1, 1, 1.4142135f)
    );

    private RgbLightAttenuation() {
    }

    public static char attenuate(int packed, BlockState destination) {
        return attenuate(packed, destination, 1.0f);
    }

    public static char attenuate(int packed, BlockState destination, float multiplier) {
        if (packed == 0) return 0;
        return PackedRgbLight.attenuate(
                packed, scaledLoss(attenuationLoss(PackedRgbLight.alpha(packed),
                        destination.getLightDampening()), multiplier)
        );
    }

    public static char attenuateRounded(int packed, float previousDistance, float nextDistance) {
        return attenuateRounded(packed, previousDistance, nextDistance, 1.0f);
    }

    public static char attenuateRounded(int packed, float previousDistance, float nextDistance,
                                        float multiplier) {
        if (packed == 0) return 0;
        return PackedRgbLight.attenuate(
                packed,
                scaledLoss(roundedAttenuationLoss(PackedRgbLight.alpha(packed),
                        previousDistance, nextDistance), multiplier)
        );
    }

    private static int scaledLoss(int loss, float multiplier) {
        return Math.max(1, Math.round(loss * multiplier));
    }

    public static int attenuationLoss(int alpha, int dampening) {
        int loss = Math.max(1, dampening);
        if (alpha % 5 == 3) loss++;
        return loss;
    }

    public static int roundedAttenuationLoss(int alpha, float previousDistance, float nextDistance) {
        int loss = Math.max(1,
                (int) Math.floor(nextDistance + 0.0001f)
                        - (int) Math.floor(previousDistance + 0.0001f));
        if (alpha % 5 == 3) loss++;
        return loss;
    }

    public static int obstaclePenalty(BlockState state) {
        return Math.max(0, Math.max(1, state.getLightDampening()) - 1);
    }

    public static float radialDistance(
            int originX, int originY, int originZ,
            int x, int y, int z
    ) {
        float dx = x - originX;
        float dy = y - originY;
        float dz = z - originZ;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz) * RADIAL_DISTANCE_SCALE;
    }

    public record Step(int x, int y, int z, float distance) {
        public boolean diagonal() {
            return Math.abs(x) + Math.abs(y) + Math.abs(z) > 1;
        }
    }
}
