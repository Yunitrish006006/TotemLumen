package dev.totem.lumen.vulkan;

/** Research exporter uses the exact same candidate transformation as the opt-in runtime. */
final class FilteredTraceResearch {
    static final String VARIANT = FilteredTraceShaderPatch.VARIANT;

    static String apply(String source) {
        return FilteredTraceShaderPatch.apply(source);
    }
}
