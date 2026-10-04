package dev.totem.lumen.render;

/** Experimental compositor policy; independent of Minecraft and the Vulkan backend. */
public final class HybridTerrainPolicy {
    public static final String PROPERTY = "totem.lumen.hybridTerrain";
    // Keep the accepted presentation path until native/MoltenVK visual gates pass.
    public static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    public static final float FADE_START = 0.8f;

    private HybridTerrainPolicy() { }

    public static int outputRows(int renderHeight, boolean hybrid) {
        if (renderHeight <= 0) throw new IllegalArgumentException("Non-positive render height");
        return Math.multiplyExact(renderHeight, hybrid ? 2 : 1);
    }

    /** Misses/non-finite depths never replace terrain; last 20% blends back to raster lighting. */
    public static float weight(float distance, float limit) {
        if (!Float.isFinite(distance) || !Float.isFinite(limit) || distance <= 0 || limit <= 0) return 0;
        float t = Math.clamp((distance / limit - FADE_START) / (1 - FADE_START), 0, 1);
        return 1 - t * t * (3 - 2 * t);
    }
}
