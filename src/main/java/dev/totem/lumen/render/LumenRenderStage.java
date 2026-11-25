package dev.totem.lumen.render;

/**
 * Coarse rendering stages for the staged Totem Lumen architecture.
 *
 * <p>These are intentionally large ownership boundaries, not individual shader helpers. Shared
 * tracing/material utilities remain reusable implementation details inside a stage.</p>
 */
public enum LumenRenderStage {
    SURFACE_CAPTURE,
    MATERIAL_RESOLVE,
    DIRECT_LIGHT,
    INDIRECT_GI,
    REFLECTION,
    TEMPORAL,
    DENOISE,
    COMPOSITE
}
