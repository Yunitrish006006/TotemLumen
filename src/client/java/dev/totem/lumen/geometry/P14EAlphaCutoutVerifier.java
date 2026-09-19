package dev.totem.lumen.geometry;

import java.util.Arrays;

/** Build-time verifier for P14E UV/mask registry and packed scene-tail semantics. */
public final class P14EAlphaCutoutVerifier {
    private P14EAlphaCutoutVerifier() {
    }

    public static void main(String[] args) {
        float[] positions = quad(0.0f);
        float[] uvs = unitUvs();
        int[] cutout = opaqueMask();
        // Make the first 32x32 texel transparent.
        cutout[0] &= ~1;

        int cutoutId = BlockModelMeshRegistry.register(positions, uvs, cutout);
        require(cutoutId > 0, "cutout mesh should allocate");
        require(
                BlockModelMeshRegistry.register(positions, uvs, cutout) == cutoutId,
                "identical textured mesh must deduplicate"
        );

        BlockModelMeshRegistry.Snapshot first = BlockModelMeshRegistry.snapshot();
        require(first.alphaMasks().size() == 1, "one non-opaque alpha mask expected");
        BlockModelMeshRegistry.Mesh cutoutMesh = mesh(first, cutoutId);
        require(cutoutMesh.alphaMaskIds()[0] == 1, "cutout quad should reference alpha mask 1");
        require(
                first.alphaMasks().getFirst().words()[0] == cutout[0],
                "packed alpha-mask bits must remain exact"
        );

        int opaqueId = BlockModelMeshRegistry.register(quad(0.25f), unitUvs(), opaqueMask());
        require(opaqueId > 0 && opaqueId != cutoutId, "second geometry should allocate another mesh");
        BlockModelMeshRegistry.Snapshot second = BlockModelMeshRegistry.snapshot();
        require(second.alphaMasks().size() == 1, "opaque mesh must not allocate an alpha mask");
        require(mesh(second, opaqueId).alphaMaskIds()[0] == 0, "opaque quad must use mask id zero");

        verifyPackedOffsets(second);
        System.out.println(
                "P14E alpha-cutout verification PASS: resolution="
                        + BlockModelMeshRegistry.ALPHA_MASK_RESOLUTION
                        + "x"
                        + BlockModelMeshRegistry.ALPHA_MASK_RESOLUTION
                        + ", masks="
                        + second.alphaMasks().size()
                        + ", cutoutMeshId="
                        + cutoutId
        );
    }

    private static BlockModelMeshRegistry.Mesh mesh(
            BlockModelMeshRegistry.Snapshot snapshot,
            int id
    ) {
        for (BlockModelMeshRegistry.Mesh mesh : snapshot.meshes()) {
            if (mesh.id() == id) return mesh;
        }
        throw new IllegalStateException("missing mesh " + id);
    }

    private static void verifyPackedOffsets(BlockModelMeshRegistry.Snapshot snapshot) {
        int expectedFirstQuad = 0;
        for (BlockModelMeshRegistry.Mesh mesh : snapshot.meshes()) {
            require(
                    mesh.firstQuad() == expectedFirstQuad,
                    "snapshot quad pool must remain contiguous"
            );
            expectedFirstQuad += mesh.quadCount();
        }
        require(
                expectedFirstQuad == snapshot.totalQuads(),
                "snapshot totalQuads must match packed meshes"
        );
    }

    private static float[] quad(float xOffset) {
        return new float[]{
                xOffset, 0.0f, 0.0f,
                xOffset + 0.5f, 0.0f, 0.0f,
                xOffset + 0.5f, 0.5f, 0.0f,
                xOffset, 0.5f, 0.0f
        };
    }

    private static float[] unitUvs() {
        return new float[]{
                0.0f, 0.0f,
                1.0f, 0.0f,
                1.0f, 1.0f,
                0.0f, 1.0f
        };
    }

    private static int[] opaqueMask() {
        int[] words = new int[BlockModelMeshRegistry.ALPHA_MASK_WORDS_PER_QUAD];
        Arrays.fill(words, -1);
        return words;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
