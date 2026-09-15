package dev.totem.lumen.mixin;

import dev.totem.lumen.gameplay.light.GameplaySpawnLighting;
import dev.totem.lumen.gameplay.light.SpawnLightContext;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replaces the hostile-mob dark-enough query while preserving the surrounding vanilla spawn rule. */
@Mixin(Monster.class)
abstract class MonsterSpawnLightingMixin {
    @Inject(method = "checkMonsterSpawnRules", at = @At("HEAD"))
    private static void totemLumen$rememberSpawnEntityType(
            EntityType<? extends Monster> entityType,
            ServerLevelAccessor level,
            EntitySpawnReason spawnReason,
            BlockPos pos,
            RandomSource random,
            CallbackInfoReturnable<Boolean> cir
    ) {
        SpawnLightContext.push(entityType);
    }

    @Inject(method = "checkMonsterSpawnRules", at = @At("RETURN"))
    private static void totemLumen$clearSpawnEntityType(
            EntityType<? extends Monster> entityType,
            ServerLevelAccessor level,
            EntitySpawnReason spawnReason,
            BlockPos pos,
            RandomSource random,
            CallbackInfoReturnable<Boolean> cir
    ) {
        SpawnLightContext.clear();
    }

    @Inject(method = "isDarkEnoughToSpawn", at = @At("HEAD"), cancellable = true)
    private static void totemLumen$useAuthoritativeGameplayLight(
            ServerLevelAccessor level,
            BlockPos pos,
            RandomSource random,
            CallbackInfoReturnable<Boolean> cir
    ) {
        cir.setReturnValue(GameplaySpawnLighting.isDarkEnough(
                level.getLevel(),
                pos,
                random,
                SpawnLightContext.current()
        ));
    }
}
