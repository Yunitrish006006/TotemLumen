package dev.totem.lumen.gameplay.light;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Immutable ordered spawn-light profile snapshot. Higher priority wins; id breaks ties deterministically. */
public final class SpawnLightProfileSet {
    public static final SpawnLightProfileSet EMPTY = new SpawnLightProfileSet(Map.of());

    private final List<Entry> ordered;
    private final Map<Identifier, SpawnLightProfile> profiles;

    private SpawnLightProfileSet(Map<Identifier, SpawnLightProfile> profiles) {
        this.profiles = Map.copyOf(profiles);
        List<Entry> entries = new ArrayList<>();
        profiles.forEach((id, profile) -> entries.add(new Entry(id, profile)));
        entries.sort(Comparator
                .comparingInt((Entry entry) -> entry.profile().priority())
                .thenComparing(entry -> entry.id().toString()));
        this.ordered = List.copyOf(entries);
    }

    public static SpawnLightProfileSet of(Map<Identifier, SpawnLightProfile> profiles) {
        return profiles.isEmpty() ? EMPTY : new SpawnLightProfileSet(profiles);
    }

    public GameplayLightSensitivity sensitivityFor(EntityType<?> entityType) {
        GameplayLightSensitivity result = GameplayLightSensitivity.DEFAULT;
        for (Entry entry : ordered) {
            if (entry.profile().matches(entityType)) {
                result = entry.profile().sensitivity();
            }
        }
        return result;
    }

    public int size() {
        return ordered.size();
    }

    public Map<Identifier, SpawnLightProfile> profiles() {
        return profiles;
    }

    private record Entry(Identifier id, SpawnLightProfile profile) {
    }
}
