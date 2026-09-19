package dev.totem.lumen.geometry;

import dev.totem.lumen.TotemLumenClient;

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
 * immutable geometry plus cutout data. Block-entity meshes instead own stable mutable ids:
 * animation updates replace the geometry behind an existing id rather than consuming a new 12-bit
 * id every frame.</p>
 */
public final class BlockModelMeshRegistry {
    public static final int MAX_MESH_ID = 0x0FFF;
    public static final int MAX_QUADS = 65_536;
    public static final int MAX_QUADS_PER_MESH = 512;
    public static final int FLOATS_PER_QUAD = 12;
    public static final int UV_FLOATS_PER_QUAD = 8;

    public static final int ALPHA_MASK_RESOLUTION = 32;
    public static final int ALPHA_MASK_WORDS_PER_QUAD =
            (ALPHA_MASK_RESOLUTION * ALPHA_MASK_RESOLUTION) / Integer.SIZE;
    public static final int MAX_ALPHA_MASK_ID = 0x0FFF;

    private static final Map<MeshKey, Integer> STATIC_IDS = new HashMap<>();
    private static final Map<DynamicMeshKey, Integer> DYNAMIC_IDS = new HashMap<>();
    private static final Map<Integer, StoredMesh> MESHES = new HashMap<>();
    private static final NavigableSet<Integer> REUSABLE_IDS = new TreeSet<>();

    private static final Map<AlphaMaskKey, Integer> ALPHA_MASK_IDS = new HashMap<>();
    private static final Map<Integer, int[]> ALPHA_MASKS = new HashMap<>();

    private static int nextId = 1;
    private static int nextAlphaMaskId = 1;
    private static int totalQuads;
    private static long revision;
    private static boolean capacityWarningLogged;
    private static boolean perMeshWarningLogged;
    private static boolean alphaMaskCapacityWarningLogged;

    private BlockModelMeshRegistry() {
    }

    /**
     * Registers an opaque geometry-only mesh. P14D and conservative outline fallbacks use this
     * overload; UVs and cutout masks are intentionally absent.
     */
    public static synchronized int register(float[] quadPositions) {
        int quadCount = validatePositions(quadPositions);
        float[] quadUvs = new float[quadCount * UV_FLOATS_PER_QUAD];
        int[] alphaMaskWords = new int[quadCount * ALPHA_MASK_WORDS_PER_QUAD];
        Arrays.fill(alphaMaskWords, -1);
        return register(quadPositions, quadUvs, alphaMaskWords);
    }

    /**
     * Registers a static P14C mesh with sprite-local UVs and one 32x32 binary alpha mask per quad.
     * A mask made entirely of one bits is encoded as mask id zero and has no shader alpha-test cost.
     */
    public static synchronized int register(
            float[] quadPositions,
            float[] quadUvs,
            int[] alphaMaskWords
    ) {
        int quadCount = validatePositions(quadPositions);
        validateTexturePayload(quadCount, quadUvs, alphaMaskWords);
        if (quadCount == 0) return 0;
        if (quadCount > MAX_QUADS_PER_MESH) {
            logPerMeshCapacity(quadCount);
            return -1;
        }

        float[] canonicalUvs = quadUvs.clone();
        for (int quad = 0; quad < quadCount; quad++) {
            int maskBase = quad * ALPHA_MASK_WORDS_PER_QUAD;
            if (isOpaqueMask(alphaMaskWords, maskBase)) {
                Arrays.fill(
                        canonicalUvs,
                        quad * UV_FLOATS_PER_QUAD,
                        (quad + 1) * UV_FLOATS_PER_QUAD,
                        0.0f
                );
            }
        }

        MeshKey key = MeshKey.of(quadPositions, canonicalUvs, alphaMaskWords);
        Integer existing = STATIC_IDS.get(key);
        if (existing != null) return existing;
        if (!hasQuadCapacity(0, quadCount) || !canAllocateId()) {
            logGlobalCapacity();
            return -1;
        }

        int[] alphaMaskIds = resolveAlphaMaskIds(quadCount, alphaMaskWords);
        if (alphaMaskIds == null) {
            logAlphaMaskCapacity();
            return -1;
        }

        int id = allocateId();
        if (id < 0) {
            logGlobalCapacity();
            return -1;
        }

        MESHES.put(
                id,
                new StoredMesh(
                        id,
                        quadPositions.clone(),
                        canonicalUvs,
                        alphaMaskIds,
                        false
                )
        );
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
            if (MeshKey.of(existing.positions).equals(MeshKey.of(quadPositions))) {
                return new DynamicUpsertResult(existingId, false, false, true);
            }
            int oldQuadCount = existing.positions.length / FLOATS_PER_QUAD;
            if (!hasQuadCapacity(oldQuadCount, quadCount)) {
                logGlobalCapacity();
                return new DynamicUpsertResult(existingId, false, false, false);
            }
            MESHES.put(existingId, opaqueDynamicMesh(existingId, quadPositions));
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
        MESHES.put(id, opaqueDynamicMesh(id, quadPositions));
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
            float[] positions = stored.positions.clone();
            float[] uvs = stored.uvs.clone();
            int[] alphaMaskIds = stored.alphaMaskIds.clone();
            int quadCount = positions.length / FLOATS_PER_QUAD;
            meshes.add(new Mesh(id, firstQuad, quadCount, positions, uvs, alphaMaskIds));
            firstQuad += quadCount;
        }

        List<Integer> alphaIds = new ArrayList<>(ALPHA_MASKS.keySet());
        alphaIds.sort(Integer::compareTo);
        List<AlphaMask> alphaMasks = new ArrayList<>(alphaIds.size());
        for (int id : alphaIds) {
            alphaMasks.add(new AlphaMask(id, ALPHA_MASKS.get(id).clone()));
        }

        return new Snapshot(List.copyOf(meshes), List.copyOf(alphaMasks), firstQuad, revision);
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

    public static synchronized int alphaMaskCount() {
        return ALPHA_MASKS.size();
    }

    public static synchronized long revision() {
        return revision;
    }

    private static StoredMesh opaqueDynamicMesh(int id, float[] positions) {
        int quadCount = positions.length / FLOATS_PER_QUAD;
        return new StoredMesh(
                id,
                positions.clone(),
                new float[quadCount * UV_FLOATS_PER_QUAD],
                new int[quadCount],
                true
        );
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

    private static void validateTexturePayload(
            int quadCount,
            float[] uvs,
            int[] alphaMaskWords
    ) {
        if (uvs == null || uvs.length != quadCount * UV_FLOATS_PER_QUAD) {
            throw new IllegalArgumentException("Quad UV array must contain 8 floats per quad");
        }
        for (float value : uvs) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("model UV coordinate must be finite");
            }
        }
        if (alphaMaskWords == null
                || alphaMaskWords.length != quadCount * ALPHA_MASK_WORDS_PER_QUAD) {
            throw new IllegalArgumentException(
                    "Alpha mask payload must contain "
                            + ALPHA_MASK_WORDS_PER_QUAD
                            + " words per quad"
            );
        }
    }

    private static int[] resolveAlphaMaskIds(int quadCount, int[] rawWords) {
        Map<AlphaMaskKey, Integer> pending = new HashMap<>();
        int proposedNext = nextAlphaMaskId;

        for (int quad = 0; quad < quadCount; quad++) {
            int base = quad * ALPHA_MASK_WORDS_PER_QUAD;
            if (isOpaqueMask(rawWords, base)) continue;
            AlphaMaskKey key = AlphaMaskKey.of(rawWords, base);
            if (ALPHA_MASK_IDS.containsKey(key) || pending.containsKey(key)) continue;
            if (proposedNext > MAX_ALPHA_MASK_ID) return null;
            pending.put(key, proposedNext++);
        }

        for (Map.Entry<AlphaMaskKey, Integer> entry : pending.entrySet()) {
            ALPHA_MASK_IDS.put(entry.getKey(), entry.getValue());
            ALPHA_MASKS.put(entry.getValue(), entry.getKey().copyWords());
        }
        nextAlphaMaskId = proposedNext;

        int[] ids = new int[quadCount];
        for (int quad = 0; quad < quadCount; quad++) {
            int base = quad * ALPHA_MASK_WORDS_PER_QUAD;
            if (isOpaqueMask(rawWords, base)) {
                ids[quad] = 0;
            } else {
                Integer id = ALPHA_MASK_IDS.get(AlphaMaskKey.of(rawWords, base));
                if (id == null) throw new IllegalStateException("alpha mask registration lost");
                ids[quad] = id;
            }
        }
        return ids;
    }

    private static boolean isOpaqueMask(int[] words, int base) {
        for (int i = 0; i < ALPHA_MASK_WORDS_PER_QUAD; i++) {
            if (words[base + i] != -1) return false;
        }
        return true;
    }

    private static boolean hasQuadCapacity(int replacingQuads, int replacementQuads) {
        return totalQuads - replacingQuads + replacementQuads <= MAX_QUADS;
    }

    private static boolean canAllocateId() {
        return !REUSABLE_IDS.isEmpty() || nextId <= MAX_MESH_ID;
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

    private static void logAlphaMaskCapacity() {
        if (!alphaMaskCapacityWarningLogged) {
            alphaMaskCapacityWarningLogged = true;
            TotemLumenClient.LOGGER.warn(
                    "P14 alpha-cutout mask capacity reached: masks={}/{}; affected meshes use conservative fallback",
                    ALPHA_MASKS.size(),
                    MAX_ALPHA_MASK_ID
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
            float[] uvs,
            int[] alphaMaskIds
    ) {
        public Mesh {
            if (id <= 0 || id > MAX_MESH_ID) {
                throw new IllegalArgumentException("mesh id out of range: " + id);
            }
            if (firstQuad < 0 || quadCount < 0
                    || positions.length != quadCount * FLOATS_PER_QUAD
                    || uvs.length != quadCount * UV_FLOATS_PER_QUAD
                    || alphaMaskIds.length != quadCount) {
                throw new IllegalArgumentException("invalid mesh payload");
            }
        }
    }

    public record AlphaMask(int id, int[] words) {
        public AlphaMask {
            if (id <= 0 || id > MAX_ALPHA_MASK_ID
                    || words.length != ALPHA_MASK_WORDS_PER_QUAD) {
                throw new IllegalArgumentException("invalid alpha mask payload");
            }
        }
    }

    public record Snapshot(
            List<Mesh> meshes,
            List<AlphaMask> alphaMasks,
            int totalQuads,
            long revision
    ) {
        public Snapshot {
            meshes = List.copyOf(meshes);
            alphaMasks = List.copyOf(alphaMasks);
            if (totalQuads < 0 || totalQuads > MAX_QUADS) {
                throw new IllegalArgumentException("invalid total quad count: " + totalQuads);
            }
        }
    }

    private record StoredMesh(
            int id,
            float[] positions,
            float[] uvs,
            int[] alphaMaskIds,
            boolean dynamic
    ) {
    }

    private static final class MeshKey {
        private final int[] bits;
        private final int hash;

        private MeshKey(int[] bits) {
            this.bits = bits;
            this.hash = Arrays.hashCode(bits);
        }

        static MeshKey of(float[] positions) {
            return of(positions, new float[0], new int[0]);
        }

        static MeshKey of(float[] positions, float[] uvs, int[] alphaMaskWords) {
            int[] bits = new int[positions.length + uvs.length + alphaMaskWords.length];
            int cursor = 0;
            for (float value : positions) {
                float normalized = value == 0.0f ? 0.0f : value;
                if (!Float.isFinite(normalized)) {
                    throw new IllegalArgumentException("model vertex coordinate must be finite");
                }
                bits[cursor++] = Float.floatToIntBits(normalized);
            }
            for (float value : uvs) {
                float normalized = value == 0.0f ? 0.0f : value;
                if (!Float.isFinite(normalized)) {
                    throw new IllegalArgumentException("model UV coordinate must be finite");
                }
                bits[cursor++] = Float.floatToIntBits(normalized);
            }
            for (int word : alphaMaskWords) bits[cursor++] = word;
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

    private static final class AlphaMaskKey {
        private final int[] words;
        private final int hash;

        private AlphaMaskKey(int[] words) {
            this.words = words;
            this.hash = Arrays.hashCode(words);
        }

        static AlphaMaskKey of(int[] source, int base) {
            return new AlphaMaskKey(
                    Arrays.copyOfRange(source, base, base + ALPHA_MASK_WORDS_PER_QUAD)
            );
        }

        int[] copyWords() {
            return words.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof AlphaMaskKey key && Arrays.equals(words, key.words);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
