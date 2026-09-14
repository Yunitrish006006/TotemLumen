package dev.totem.lumen.material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable integer material IDs for compact voxel storage and later GPU upload.
 * ID 0 is permanently reserved for air.
 */
public final class MaterialRegistry {
    public static final int AIR_ID = 0;

    private final Map<MaterialDefinition, Integer> ids = new LinkedHashMap<>();
    private final List<MaterialDefinition> definitions = new ArrayList<>();

    public MaterialRegistry() {
        ids.put(MaterialDefinition.AIR, AIR_ID);
        definitions.add(MaterialDefinition.AIR);
    }

    public synchronized int idFor(MaterialDefinition definition) {
        Integer existing = ids.get(definition);
        if (existing != null) {
            return existing;
        }

        int id = definitions.size();
        definitions.add(definition);
        ids.put(definition, id);
        return id;
    }

    public synchronized MaterialDefinition definition(int id) {
        return definitions.get(id);
    }

    public synchronized int size() {
        return definitions.size();
    }

    public synchronized List<MaterialDefinition> snapshot() {
        return List.copyOf(definitions);
    }
}
