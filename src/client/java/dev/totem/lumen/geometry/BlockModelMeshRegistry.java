package dev.totem.lumen.geometry;

import dev.totem.lumen.TotemLumenClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-owned immutable block-model geometry registry used by P14C.
 *
 * <p>Mesh id zero is reserved for an intentionally empty model. Non-zero ids fit in the low
 * 12 bits of {@code MODEL_MESH} geometry codes. Geometry is deduplicated from emitted quad vertex
 * positions so different block states/resource-pack models can share one GPU mesh.</p>
 */
public final class BlockModelMeshRegistry {
    public static final int MAX_MESH_ID = 0x0FFF;
    public static final int MAX_QUADS = 65_536;
    public static final int MAX_QUADS_PER_MESH = 512;
    public static final int FLOATS_PER_QUAD = 12;

    private static final Map<MeshKey, Integer> IDS = new HashMap<>();
    private static final List<Mesh> MESHES = new ArrayList<>();

    private static int totalQuads;
    private static boolean capacityWarningLogged;
    private static boolean perMeshWarningLogged;

    private BlockModelMeshRegistry() {
    }

    /**
     * Registers local-space quads and returns a 12-bit mesh id. Zero represents no geometry;
     * negative one means the registry cannot represent this mesh and the caller should use a
     * conservative fallback.
     */
    public static synchronized int register(float[] quadPositions) {
        if (quadPositions.length == 0) {
            return 0;
        }
        if (quadPositions.length % FLOATS_PER_QUAD != 0) {
            throw new IllegalArgumentException("Quad position array must contain 12 floats per quad");
        }

        int quadCount = quadPositions.length / FLOATS_PER_QUAD;
        if (quadCount > MAX_QUADS_PER_MESH) {
            if (!perMeshWarningLogged) {
                perMeshWarningLogged = true;
                TotemLumenClient.LOGGER.warn(
                        "P14C model mesh exceeds per-mesh quad cap: quads={}, cap={}; using conservative fallback",
                        quadCount,
                        MAX_QUADS_PER_MESH
                );
            }
            return -1;
        }

        MeshKey key = MeshKey.of(quadPositions);
        Integer existing = IDS.get(key);
        if (existing != null) {
            return existing;
        }

        int nextId = MESHES.size() + 1;
        if (nextId > MAX_MESH_ID || totalQuads + quadCount > MAX_QUADS) {
            if (!capacityWarningLogged) {
                capacityWarningLogged = true;
                TotemLumenClient.LOGGER.warn(
                        "P14C model mesh registry capacity reached: meshes={}/{}, quads={}/{}; new meshes use conservative fallback",
                        MESHES.size(), MAX_MESH_ID, totalQuads, MAX_QUADS
                );
            }
            return -1;
        }

        float[] owned = quadPositions.clone();
        Mesh mesh = new Mesh(nextId, totalQuads, quadCount, owned);
        MESHES.add(mesh);
        IDS.put(key, nextId);
        totalQuads += quadCount;
        return nextId;
    }

    public static synchronized Snapshot snapshot() {
        List<Mesh> meshes = new ArrayList<>(MESHES.size());
        for (Mesh mesh : MESHES) {
            meshes.add(new Mesh(mesh.id(), mesh.firstQuad(), mesh.quadCount(), mesh.positions().clone()));
        }
        return new Snapshot(List.copyOf(meshes), totalQuads);
    }

    public static synchronized int meshCount() {
        return MESHES.size();
    }

    public static synchronized int quadCount() {
        return totalQuads;
    }

    public record Mesh(int id, int firstQuad, int quadCount, float[] positions) {
        public Mesh {
            if (id <= 0 || id > MAX_MESH_ID) {
                throw new IllegalArgumentException("mesh id out of range: " + id);
            }
            if (firstQuad < 0 || quadCount < 0 || positions.length != quadCount * FLOATS_PER_QUAD) {
                throw new IllegalArgumentException("invalid mesh payload");
            }
        }
    }

    public record Snapshot(List<Mesh> meshes, int totalQuads) {
        public Snapshot {
            meshes = List.copyOf(meshes);
            if (totalQuads < 0 || totalQuads > MAX_QUADS) {
                throw new IllegalArgumentException("invalid total quad count: " + totalQuads);
            }
        }
    }

    private static final class MeshKey {
        private final int[] bits;
        private final int hash;

        private MeshKey(int[] bits) {
            this.bits = bits;
            this.hash = Arrays.hashCode(bits);
        }

        static MeshKey of(float[] positions) {
            int[] bits = new int[positions.length];
            for (int index = 0; index < positions.length; index++) {
                float value = positions[index] == 0.0f ? 0.0f : positions[index];
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("model vertex coordinate must be finite");
                }
                bits[index] = Float.floatToIntBits(value);
            }
            return new MeshKey(bits);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof MeshKey key && Arrays.equals(bits, key.bits);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
