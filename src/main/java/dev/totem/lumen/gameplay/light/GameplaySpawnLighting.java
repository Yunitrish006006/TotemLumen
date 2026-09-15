package dev.totem.lumen.gameplay.light;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.LightLayer;

/** Server-authoritative hostile-mob light predicate. */
public final class GameplaySpawnLighting {
    private GameplaySpawnLighting() {
    }

    public static boolean isDarkEnough(
            ServerLevel level,
            BlockPos pos,
            RandomSource random,
            EntityType<?> entityType
    ) {
        ServerGameplayLightEngine engine = ServerGameplayLightingManager.engine(level);
        if (!engine.isStable(pos)) {
            // Conservative convergence rule: a stale-dark value must never allow a natural hostile
            // spawn immediately after an authoritative light-source/occlusion change.
            return false;
        }

        int rawSky = level.getBrightness(LightLayer.SKY, pos);
        if (rawSky > random.nextInt(32)) {
            return false;
        }

        GameplayLightSensitivity sensitivity = entityType == null
                ? GameplayLightSensitivity.DEFAULT
                : SpawnLightProfilesReloadListener.currentProfiles().sensitivityFor(entityType);

        int blockRgb = engine.packedBlockLight(pos);
        int blockBrightness = Math.round(PackedRgbLight.effectiveBrightness(blockRgb, sensitivity));

        int environmentBrightness = 0;
        DimensionLightingRule dimensionRule = DimensionLightingRulesReloadListener.currentRules()
                .ruleFor(level.dimension().identifier());
        if (dimensionRule != null && dimensionRule.affectsSpawning()) {
            environmentBrightness = Math.round(
                    sensitivity.environmentBrightness(dimensionRule.packedEnvironment())
            );
        }

        int effectiveBlock = Math.max(blockBrightness, environmentBrightness);
        var dimensionType = level.dimensionType();
        if (effectiveBlock > dimensionType.monsterSpawnBlockLightLimit()) {
            return false;
        }

        int adjustedSky = Math.max(0, rawSky - (level.isThundering() ? 10 : level.getSkyDarken()));
        int localBrightness = Math.max(adjustedSky, effectiveBlock);
        return localBrightness <= dimensionType.monsterSpawnLightTest().sample(random);
    }
}
