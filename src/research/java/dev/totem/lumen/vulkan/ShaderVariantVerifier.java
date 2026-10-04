package dev.totem.lumen.vulkan;

/** Source/routing checks for the 2026-10-03 block-tint ABI revision. No GPU required.
 * The CR2 trace transform is unchanged; these sources are NOT the previously timed binaries.
 */
public final class ShaderVariantVerifier {
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("Expected variant under test");
        String variant = args[0];
        String full = P12FullBasePipeline.buildSourceForVerification();
        String reflection = P16MultipassReflection.buildSourceForVerification();
        String candidate = FilteredTraceShaderPatch.VARIANT;
        require(LumenShaderVariant.select("baseline", full) == full, "Baseline changed");
        require(LumenShaderVariant.hash(LumenShaderVariant.select(candidate, full)).equals(
                "66869b9374362efb6878a1446e838b0ff89293288eea8ba7400823aa84e05c06"), "Full candidate drift");
        require(LumenShaderVariant.hash(LumenShaderVariant.select(candidate, reflection)).equals(
                "f823a2ff9238219d47e942ddd85391ee806ffaa19c188fdadab693e0c3d6b5f9"), "Reflection candidate drift");
        rejects(() -> LumenShaderVariant.select("typo", full));
        rejects(() -> LumenShaderVariant.select(candidate, "missing anchors"));
        rejects(() -> LumenShaderVariant.select(candidate, full + full));
        rejects(() -> LumenShaderVariant.select(candidate,
                full.replace("uint maxLayers = clamp(scene.data[61], 1u, 8u);", "uint maxLayers = 9u;")));
        rejects(() -> LumenShaderVariant.select(candidate, LumenShaderVariant.select(candidate, full)));
        require(P12FullBasePipeline.buildRuntimeSource().equals(LumenShaderVariant.select(variant, full)),
                "Full runtime route differs");
        require(P16MultipassReflection.buildRuntimeSource().equals(LumenShaderVariant.select(variant, reflection)),
                "Reflection runtime route differs");
        String oldProperty = System.getProperty(LumenShaderVariant.PROPERTY);
        try {
            System.setProperty(LumenShaderVariant.PROPERTY, variant.equals(candidate) ? "baseline" : candidate);
            require(P12FullBasePipeline.buildRuntimeSource().equals(LumenShaderVariant.select(variant, full)),
                    "Variant changed within a process");
            require(P16MultipassReflection.buildRuntimeSource().equals(LumenShaderVariant.select(variant, reflection)),
                    "Reflection variant changed within a process");
        } finally {
            if (oldProperty == null) System.clearProperty(LumenShaderVariant.PROPERTY);
            else System.setProperty(LumenShaderVariant.PROPERTY, oldProperty);
        }
        System.out.println("Shader variant verification PASS: " + variant
                + "; block-tint revision hashes, baseline routing identity, invalid/drift rejection, runtime routing, restart boundary");
    }

    private static void rejects(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid source/variant rejection");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
