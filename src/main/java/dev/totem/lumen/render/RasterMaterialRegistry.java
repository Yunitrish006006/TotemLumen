package dev.totem.lumen.render;

import dev.totem.lumen.material.MaterialDefinition;
import dev.totem.lumen.material.MaterialFlags;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Session-stable material IDs for the staged raster renderer.
 *
 * <p>ID zero is reserved for air/unknown/fallback. Definitions are immutable, so a resource/rule
 * change that changes a definition receives a new ID while already-published sections remain valid.
 * The registry deliberately reuses Totem's existing MaterialDefinition instead of introducing a
 * second raster-specific material model.</p>
 */
public final class RasterMaterialRegistry {
    public static final int MAX_MATERIAL_ID = 0xFFFF;

    private final Map<MaterialDefinition, Integer> ids = new HashMap<>();
    private final Map<Integer, MaterialDefinition> materials = new HashMap<>();
    private int nextId = 1;
    private long revision;

    public synchronized int register(MaterialDefinition material) {
        Objects.requireNonNull(material, "material");
        if (material == MaterialDefinition.AIR || material.has(MaterialFlags.AIR)) return 0;

        Integer existing = ids.get(material);
        if (existing != null) return existing;
        if (nextId > MAX_MATERIAL_ID) return 0;

        int id = nextId++;
        ids.put(material, id);
        materials.put(id, material);
        revision++;
        return id;
    }

    public synchronized MaterialDefinition material(int id) {
        return id == 0 ? MaterialDefinition.AIR : materials.get(id);
    }

    public synchronized int size() {
        return materials.size();
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized Snapshot snapshot() {
        List<Entry> entries = materials.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new Entry(entry.getKey(), entry.getValue()))
                .toList();
        return new Snapshot(revision, entries);
    }

    public record Entry(int id, MaterialDefinition material) {
        public Entry {
            if (id <= 0 || id > MAX_MATERIAL_ID) {
                throw new IllegalArgumentException("material id out of range: " + id);
            }
            Objects.requireNonNull(material, "material");
        }
    }

    public record Snapshot(long revision, List<Entry> entries) {
        public Snapshot {
            entries = List.copyOf(entries);
        }
    }
}
