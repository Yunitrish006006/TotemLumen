package dev.totem.lumen.geometry;

/** Build-time verifier for P14D stable mutable mesh-slot semantics. */
public final class P14DDynamicMeshVerifier {
    private P14DDynamicMeshVerifier() {
    }

    public static void main(String[] args) {
        BlockModelMeshRegistry.clearDynamic();

        float[] staticQuad = quad(0.0f);
        int staticId = BlockModelMeshRegistry.register(staticQuad);
        require(staticId > 0, "static mesh should allocate an id");
        require(BlockModelMeshRegistry.register(staticQuad) == staticId, "static mesh dedupe must be stable");

        BlockModelMeshRegistry.DynamicMeshKey firstKey =
                new BlockModelMeshRegistry.DynamicMeshKey("minecraft:overworld", 1, 64, 1);
        float[] firstPose = quad(0.10f);
        var first = BlockModelMeshRegistry.upsertDynamic(firstKey, firstPose);
        require(first.accepted(), "first dynamic mesh registration should be accepted");
        require(first.firstRegistration(), "first dynamic mesh registration should be marked new");
        require(first.meshId() > 0 && first.meshId() != staticId, "dynamic id must not collide with live static id");

        int stableId = first.meshId();
        long beforeAnimationRevision = BlockModelMeshRegistry.revision();
        float[] secondPose = quad(0.20f);
        var animated = BlockModelMeshRegistry.upsertDynamic(firstKey, secondPose);
        require(animated.accepted(), "animated pose should be accepted");
        require(animated.meshId() == stableId, "animation must retain the same mesh id");
        require(!animated.firstRegistration(), "animation must not allocate another logical mesh");
        require(animated.changed(), "changed animation pose should advance mesh revision");
        require(BlockModelMeshRegistry.revision() > beforeAnimationRevision, "animation must advance registry revision");
        require(equalBits(BlockModelMeshRegistry.positionsForMesh(stableId), secondPose), "live mesh payload must be replaced");

        long unchangedRevision = BlockModelMeshRegistry.revision();
        var unchanged = BlockModelMeshRegistry.upsertDynamic(firstKey, secondPose);
        require(!unchanged.changed(), "identical pose must not dirty the mesh tail");
        require(BlockModelMeshRegistry.revision() == unchangedRevision, "identical pose must not advance revision");

        BlockModelMeshRegistry.Snapshot withDynamic = BlockModelMeshRegistry.snapshot();
        verifyPackedOffsets(withDynamic);
        require(withDynamic.totalQuads() == 2, "one static + one dynamic quad expected");

        require(BlockModelMeshRegistry.releaseDynamic(firstKey), "loaded dynamic mesh should release");
        require(BlockModelMeshRegistry.positionsForMesh(stableId) == null, "released mesh payload must disappear");

        BlockModelMeshRegistry.DynamicMeshKey secondKey =
                new BlockModelMeshRegistry.DynamicMeshKey("minecraft:overworld", 2, 64, 2);
        var reused = BlockModelMeshRegistry.upsertDynamic(secondKey, quad(0.30f));
        require(reused.accepted(), "replacement dynamic mesh should allocate");
        require(reused.meshId() == stableId, "released dynamic id should be reused before consuming 12-bit id space");

        BlockModelMeshRegistry.Snapshot afterReuse = BlockModelMeshRegistry.snapshot();
        verifyPackedOffsets(afterReuse);
        require(afterReuse.totalQuads() == 2, "packed quad count should remain compact after id reuse");

        BlockModelMeshRegistry.clearDynamic();
        System.out.println(
                "P14D dynamic mesh verification PASS: staticId=" + staticId
                        + ", stableDynamicId=" + stableId
                        + ", finalRevision=" + BlockModelMeshRegistry.revision()
        );
    }

    private static void verifyPackedOffsets(BlockModelMeshRegistry.Snapshot snapshot) {
        int expectedFirstQuad = 0;
        int lastId = 0;
        for (BlockModelMeshRegistry.Mesh mesh : snapshot.meshes()) {
            require(mesh.id() > lastId, "snapshot mesh ids must be strictly sorted");
            require(mesh.firstQuad() == expectedFirstQuad, "snapshot quad pool must remain contiguous despite id holes");
            expectedFirstQuad += mesh.quadCount();
            lastId = mesh.id();
        }
        require(expectedFirstQuad == snapshot.totalQuads(), "snapshot totalQuads must match packed mesh payloads");
    }

    private static float[] quad(float xOffset) {
        return new float[]{
                xOffset, 0.0f, 0.0f,
                xOffset + 0.5f, 0.0f, 0.0f,
                xOffset + 0.5f, 0.5f, 0.0f,
                xOffset, 0.5f, 0.0f
        };
    }

    private static boolean equalBits(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length) return false;
        for (int i = 0; i < left.length; i++) {
            if (Float.floatToIntBits(left[i]) != Float.floatToIntBits(right[i])) return false;
        }
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
