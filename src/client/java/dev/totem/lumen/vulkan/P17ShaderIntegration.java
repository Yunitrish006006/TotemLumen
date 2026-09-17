package dev.totem.lumen.vulkan;

/**
 * Public bridge used by the runtime mixin to attach P17 dynamic-entity tracing to the
 * production base shader without exposing the implementation class itself.
 */
public final class P17ShaderIntegration {
    private P17ShaderIntegration() {
    }

    public static String apply(String source) {
        return P17DynamicEntityShaderPatch.apply(source);
    }
}
