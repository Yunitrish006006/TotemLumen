package dev.totem.lumen.geometry;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.geometry.QuadSurface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Client-owned model geometry registry shared by P14C static block models and P14D block-entity
 * models.
 *
 * <p>Mesh id zero is reserved for an intentionally empty model. Static meshes are deduplicated by
 * immutable vertex positions. Block-entity meshes instead own stable mutable ids: animation updates
 * replace the geometry behind an existing id rather than consuming a new 12-bit id every frame.</p>
 */
public final class BlockModelMeshRegistry {
    public static final int MAX_MESH_ID = 0x0FFF;
    public static final int MAX_QUADS = 65_536;
    public static final int MAX_QUADS_PER_MESH = 512;
    public static final int FLOATS_PER_QUAD = 12;

    private static final Map<MeshKey, Integer> STATIC_IDS = new HashMap<>();
    private static final Map<DynamicMeshKey, Integer> DYNAMIC_IDS = new HashMap<>();
    private static final Map<Integer, StoredMesh> MESHES = new HashMap<>();
    private static final NavigableSet<Integer> REUSABLE_IDS = new TreeSet<>();

    private static int nextId = 1;
    private static int totalQuads;
    private static long revision;
    private static boolean capacityWarningLogged;
    private static boolean perMeshWarningLogged;

    private BlockModelMeshRegistry() {
    }

    public static synchronized int register(float[] quadPositions) {
        int quadCount = validatePositions(quadPositions);
        return register(quadPositions, untexturedSurfaces(quadCount));
    }

    public static synchronized int register(
            float[] quadPositions,
            QuadSurface[] quadSurfaces
    ) {
        int quadCount = validatePositions(quadPositions);
        validateSurfaces(quadSurfaces, quadCount);
        if (quadCount == 0) return 0;
        if (quadCount > MAX_QUADS_PER_MESH) {
            logPerMeshCapacity(quadCount);
            return -1;
        }

        MeshKey key = MeshKey.of(quadPositions, quadSurfaces);
        Integer existing = STATIC_IDS.get(key);
        if (existing != null) return existing;
        if (!hasQuadCapacity(0, quadCount)) {
            logGlobalCapacity();
            return -1;
        }

        int id = allocateId();
        if (id < 0) {
            logGlobalCapacity();
            return -1;
        }

        MESHES.put(id, new StoredMesh(
                id,
                quadPositions.clone(),
                quadSurfaces.clone(),
                false
        ));
        STATIC_IDS.put(key, id);
        totalQuads += quadCount;
        revision++;
        return id;
    }

    public static synchronized DynamicUpsertResult upsertDynamic(
            DynamicMeshKey key,
            float[] quadPositions
    ) {
        if (key == null) throw new IllegalArgumentException("dynamic mesh key cannot be null");
        int quadCount = validatePositions(quadPositions);
        if (quadCount <= 0 || quadCount > MAX_QUADS_PER_MESH) {
            if (quadCount > MAX_QUADS_PER_MESH) logPerMeshCapacity(quadCount);
            Integer existing = DYNAMIC_IDS.get(key);
            return new DynamicUpsertResult(existing == null ? -1 : existing, false, false, false);
        }

        Integer existingId = DYNAMIC_IDS.get(key);
        if (existingId != null) {
            StoredMesh existing = MESHES.get(existingId);
            if (existing == null || !existing.dynamic) {
                throw new IllegalStateException("dynamic mesh id is not backed by a dynamic entry: " + existingId);
            }
            QuadSurface[] surfaces = untexturedSurfaces(quadCount);
            if (MeshKey.of(existing.positions, existing.surfaces)
                    .equals(MeshKey.of(quadPositions, surfaces))) {
                return new DynamicUpsertResult(existingId, false, false, true);
            }
            int oldQuadCount = existing.positions.length / FLOATS_PER_QUAD;
            if (!hasQuadCapacity(oldQuadCount, quadCount)) {
                logGlobalCapacity();
                return new DynamicUpsertResult(existingId, false, false, false);
            }
            MESHES.put(existingId, new StoredMesh(
                    existingId,
                    quadPositions.clone(),
                    surfaces,
                    true
            ));
            totalQuads += quadCount - oldQuadCount;
            revision++;
            return new DynamicUpsertResult(existingId, false, true, true);
        }

        if (!hasQuadCapacity(0, quadCount)) {
            logGlobalCapacity();
            return new DynamicUpsertResult(-1, false, false, false);
        }
        int id = allocateId();
        if (id < 0) {
            logGlobalCapacity();
            return new DynamicUpsertResult(-1, false, false, false);
        }

        DYNAMIC_IDS.put(key, id);
        MESHES.put(id, new StoredMesh(
                id,
                quadPositions.clone(),
                untexturedSurfaces(quadCount),
                true
        ));
        totalQuads += quadCount;
        revision++;
        return new DynamicUpsertResult(id, true, true, true);
    }

    public static synchronized boolean releaseDynamic(DynamicMeshKey key) {
        Integer id = DYNAMIC_IDS.remove(key);
        if (id == null) return false;
        StoredMesh removed = MESHES.remove(id);
        if (removed != null) totalQuads -= removed.positions.length / FLOATS_PER_QUAD;
        REUSABLE_IDS.add(id);
        revision++;
        return true;
    }

    public static synchronized int releaseDynamicIf(Predicate<DynamicMeshKey> predicate) {
        List<DynamicMeshKey> keys = new ArrayList<>();
        for (DynamicMeshKey key : DYNAMIC_IDS.keySet()) {
            if (predicate.test(key)) keys.add(key);
        }
        for (DynamicMeshKey key : keys) releaseDynamic(key);
        return keys.size();
    }

    public static synchronized void clearDynamic() {
        releaseDynamicIf(key -> true);
    }

    public static synchronized Snapshot snapshot() {
        List<Integer> ids = new ArrayList<>(MESHES.keySet());
        ids.sort(Integer::compareTo);
        List<Mesh> meshes = new ArrayList<>(ids.size());
        int firstQuad = 0;
        for (int id : ids) {
            StoredMesh stored = MESHES.get(id);
            float[] owned = stored.positions.clone();
            int quadCount = owned.length / FLOATS_PER_QUAD;
            meshes.add(new Mesh(
                    id,
                    firstQuad,
                    quadCount,
                    owned,
                    stored.surfaces.clone()
            ));
            firstQuad += quadCount;
        }
        return new Snapshot(List.copyOf(meshes), firstQuad, revision);
    }

    /** Returns a defensive copy of one currently-live mesh payload, or an empty array for id zero. */
    public static synchronized float[] positionsForMesh(int meshId) {
        if (meshId == 0) return new float[0];
        StoredMesh mesh = MESHES.get(meshId);
        return mesh == null ? null : mesh.positions.clone();
    }

    public static synchronized int meshCount() {
        return MESHES.size();
    }

    public static synchronized int dynamicMeshCount() {
        return DYNAMIC_IDS.size();
    }

    public static synchronized int quadCount() {
        return totalQuads;
    }

    public static synchronized long revision() {
        return revision;
    }

    private static int validatePositions(float[] positions) {
        if (positions == null) throw new IllegalArgumentException("quad positions cannot be null");
        if (positions.length % FLOATS_PER_QUAD != 0) {
            throw new IllegalArgumentException("Quad position array must contain 12 floats per quad");
        }
        for (float value : positions) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("model vertex coordinate must be finite");
            }
        }
        return positions.length / FLOATS_PER_QUAD;
    }

    private static void validateSurfaces(QuadSurface[] surfaces, int quadCount) {
        if (surfaces == null) {
            throw new IllegalArgumentException("quad surfaces cannot be null");
        }
        if (surfaces.length != quadCount) {
            throw new IllegalArgumentException(
                    "quad surface count must match geometry: surfaces="
                            + surfaces.length + ", quads=" + quadCount
            );
        }
        for (QuadSurface surface : surfaces) {
            if (surface == null) {
                throw new IllegalArgumentException("quad surface cannot be null");
            }
        }
    }

    private static QuadSurface[] untexturedSurfaces(int quadCount) {
        QuadSurface[] surfaces = new QuadSurface[quadCount];
        Arrays.fill(surfaces, QuadSurface.UNTEXTURED);
        return surfaces;
    }

    private static boolean hasQuadCapacity(int replacingQuads, int replacementQuads) {
        return totalQuads - replacingQuads + replacementQuads <= MAX_QUADS;
    }

    private static int allocateId() {
        Integer reusable = REUSABLE_IDS.pollFirst();
        if (reusable != null) return reusable;
        if (nextId > MAX_MESH_ID) return -1;
        return nextId++;
    }

    private static void logPerMeshCapacity(int quadCount) {
        if (!perMeshWarningLogged) {
            perMeshWarningLogged = true;
            TotemLumenClient.LOGGER.warn(
                    "P14 model mesh exceeds per-mesh quad cap: quads={}, cap={}; using conservative fallback",
                    quadCount,
                    MAX_QUADS_PER_MESH
            );
        }
    }

    private static void logGlobalCapacity() {
        if (!capacityWarningLogged) {
            capacityWarningLogged = true;
            TotemLumenClient.LOGGER.warn(
                    "P14 model mesh registry capacity reached: meshes={}/{}, quads={}/{}; new meshes use conservative fallback",
                    MESHES.size(), MAX_MESH_ID, totalQuads, MAX_QUADS
            );
        }
    }

    public record DynamicMeshKey(String dimensionId, int x, int y, int z) {
        public DynamicMeshKey {
            if (dimensionId == null || dimensionId.isBlank()) {
                throw new IllegalArgumentException("dimension id cannot be blank");
            }
        }
    }

    public record DynamicUpsertResult(int meshId, boolean firstRegistration, boolean changed, boolean accepted) {
    }

    public record Mesh(
            int id,
            int firstQuad,
            int quadCount,
            float[] positions,
            QuadSurface[] surfaces
    ) {
        public Mesh {
            if (id <= 0 || id > MAX_MESH_ID) {
                throw new IllegalArgumentException("mesh id out of range: " + id);
            }
            if (firstQuad < 0 || quadCount < 0 || positions.length != quadCount * FLOATS_PER_QUAD) {
                throw new IllegalArgumentException("invalid mesh payload");
            }
            validateSurfaces(surfaces, quadCount);
        }
    }

    public record Snapshot(List<Mesh> meshes, int totalQuads, long revision) {
        public Snapshot {
            meshes = List.copyOf(meshes);
            if (totalQuads < 0 || totalQuads > MAX_QUADS) {
                throw new IllegalArgumentException("invalid total quad count: " + totalQuads);
            }
        }
    }

    private record StoredMesh(
            int id,
            float[] positions,
            QuadSurface[] surfaces,
            boolean dynamic
    ) {
    }

    private static final class MeshKey {
        private final int[] bits;
        private final QuadSurface[] surfaces;
        private final int hash;

        private MeshKey(int[] bits, QuadSurface[] surfaces) {
            this.bits = bits;
            this.surfaces = surfaces;
            this.hash = 31 * Arrays.hashCode(bits) + Arrays.hashCode(surfaces);
        }

        static MeshKey of(float[] positions, QuadSurface[] surfaces) {
            int[] bits = new int[positions.length];
            for (int index = 0; index < positions.length; index++) {
                float value = positions[index] == 0.0f ? 0.0f : positions[index];
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("model vertex coordinate must be finite");
                }
                bits[index] = Float.floatToIntBits(value);
            }
            return new MeshKey(bits, surfaces.clone());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof MeshKey key
                    && Arrays.equals(bits, key.bits)
                    && Arrays.equals(surfaces, key.surfaces);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
