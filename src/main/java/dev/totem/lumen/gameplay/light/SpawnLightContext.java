package dev.totem.lumen.gameplay.light;

import net.minecraft.world.entity.EntityType;

/** Carries the current Monster spawn-rule entity type into the vanilla dark-enough helper. */
public final class SpawnLightContext {
    private static final ThreadLocal<EntityType<?>> CURRENT = new ThreadLocal<>();

    private SpawnLightContext() {
    }

    public static void push(EntityType<?> entityType) {
        CURRENT.set(entityType);
    }

    public static EntityType<?> current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
