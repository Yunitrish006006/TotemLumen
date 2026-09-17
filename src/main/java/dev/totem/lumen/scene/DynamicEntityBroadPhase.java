package dev.totem.lumen.scene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CPU-side broad phase for dynamic entity geometry.
 *
 * <p>Entity AABBs are binned into every 16x16x16 section they overlap. The GPU representation can
 * reuse the existing section traversal/lookup domain instead of linearly scanning every dynamic
 * entity for every ray. A fixed per-section capacity is enforced here so overflow is explicit and
 * measurable before a matching GPU ABI is introduced.</p>
 */
public final class DynamicEntityBroadPhase {
    public static final int DEFAULT_MAX_ENTITIES_PER_SECTION = 32;

    private static final int SECTION_SIZE = 16;

    private final Map<SectionKey, int[]> buckets;
    private final int overflowAssignments;

    private DynamicEntityBroadPhase(Map<SectionKey, int[]> buckets, int overflowAssignments) {
        this.buckets = buckets;
        this.overflowAssignments = overflowAssignments;
    }

    public static DynamicEntityBroadPhase build(List<DynamicEntitySnapshot> entities) {
        return build(entities, DEFAULT_MAX_ENTITIES_PER_SECTION);
    }

    public static DynamicEntityBroadPhase build(
            List<DynamicEntitySnapshot> entities,
            int maxEntitiesPerSection
    ) {
        Objects.requireNonNull(entities, "entities");
        if (maxEntitiesPerSection <= 0) {
            throw new IllegalArgumentException("maxEntitiesPerSection must be positive");
        }

        Map<SectionKey, List<Integer>> mutableBuckets = new LinkedHashMap<>();
        int overflow = 0;
        for (int entityIndex = 0; entityIndex < entities.size(); entityIndex++) {
            DynamicEntitySnapshot entity = Objects.requireNonNull(
                    entities.get(entityIndex),
                    "entities[" + entityIndex + "]"
            );

            int minSectionX = worldToSection(entity.minX());
            int minSectionY = worldToSection(entity.minY());
            int minSectionZ = worldToSection(entity.minZ());
            int maxSectionX = worldToSection(entity.maxX());
            int maxSectionY = worldToSection(entity.maxY());
            int maxSectionZ = worldToSection(entity.maxZ());

            for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
                        SectionKey key = new SectionKey(
                                entity.dimensionId(),
                                sectionX,
                                sectionY,
                                sectionZ
                        );
                        List<Integer> bucket = mutableBuckets.computeIfAbsent(
                                key,
                                ignored -> new ArrayList<>()
                        );
                        if (bucket.size() < maxEntitiesPerSection) {
                            bucket.add(entityIndex);
                        } else {
                            overflow++;
                        }
                    }
                }
            }
        }

        Map<SectionKey, int[]> packed = new HashMap<>(mutableBuckets.size());
        mutableBuckets.forEach((key, indices) -> {
            int[] values = new int[indices.size()];
            for (int i = 0; i < indices.size(); i++) {
                values[i] = indices.get(i);
            }
            packed.put(key, values);
        });
        return new DynamicEntityBroadPhase(Collections.unmodifiableMap(packed), overflow);
    }

    public int[] entityIndices(SectionKey section) {
        int[] values = buckets.get(section);
        return values == null ? new int[0] : values.clone();
    }

    public int bucketCount() {
        return buckets.size();
    }

    public int overflowAssignments() {
        return overflowAssignments;
    }

    public Map<SectionKey, int[]> copyBuckets() {
        Map<SectionKey, int[]> copy = new HashMap<>(buckets.size());
        buckets.forEach((key, value) -> copy.put(key, value.clone()));
        return Collections.unmodifiableMap(copy);
    }

    private static int worldToSection(float coordinate) {
        int block = (int) Math.floor(coordinate);
        return Math.floorDiv(block, SECTION_SIZE);
    }
}
