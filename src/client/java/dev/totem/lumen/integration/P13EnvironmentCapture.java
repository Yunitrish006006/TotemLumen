package dev.totem.lumen.integration;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.scene.EnvironmentFrameState;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.MoonPhase;

/** Captures dimension/time-of-day/lunar-phase state before SceneExtractionBridge snapshots the frame. */
public final class P13EnvironmentCapture {
    private static boolean initialized;
    private static String lastDimensionId;

    private P13EnvironmentCapture() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;

        LevelExtractionEvents.END_EXTRACTION.register(context -> capture(context.level()));
        TotemLumenClient.LOGGER.info("P13 environment capture registered");
    }

    private static void capture(ClientLevel level) {
        String dimensionId = level.dimension().identifier().toString();
        long overworldClockTime = level.getOverworldClockTime();
        MoonPhase moonPhase = level.environmentAttributes()
                .getDimensionValue(EnvironmentAttributes.MOON_PHASE);
        EnvironmentFrameState.capture(dimensionId, overworldClockTime, moonPhase.index());

        if (!dimensionId.equals(lastDimensionId)) {
            lastDimensionId = dimensionId;
            TotemLumenClient.LOGGER.info(
                    "P13 environment source active: dimension={}, overworldClockTime={}, moonPhase={}",
                    dimensionId,
                    overworldClockTime,
                    moonPhase.getSerializedName()
            );
        }
    }
}
