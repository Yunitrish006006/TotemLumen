package dev.totem.lumen.gameplay.light;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;

import java.util.List;
import java.util.Set;

/** One data-driven entity selector plus its gameplay-light spectral response. */
public final class SpawnLightProfile {
    private final int priority;
    private final Set<Identifier> entities;
    private final List<TagKey<EntityType<?>>> entityTags;
    private final GameplayLightSensitivity sensitivity;

    public SpawnLightProfile(
            int priority,
            Set<Identifier> entities,
            List<Identifier> entityTagIds,
            GameplayLightSensitivity sensitivity
    ) {
        this.priority = priority;
        this.entities = Set.copyOf(entities);
        this.entityTags = entityTagIds.stream()
                .map(id -> TagKey.create(Registries.ENTITY_TYPE, id))
                .toList();
        this.sensitivity = java.util.Objects.requireNonNull(sensitivity, "sensitivity");
        if (this.entities.isEmpty() && this.entityTags.isEmpty()) {
            throw new IllegalArgumentException("spawn-light profile must select at least one entity or entity tag");
        }
    }

    public int priority() {
        return priority;
    }

    public GameplayLightSensitivity sensitivity() {
        return sensitivity;
    }

    public boolean matches(EntityType<?> entityType) {
        Identifier entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
        if (entities.contains(entityId)) {
            return true;
        }
        for (TagKey<EntityType<?>> tag : entityTags) {
            if (entityType.builtInRegistryHolder().is(tag)) {
                return true;
            }
        }
        return false;
    }
}
