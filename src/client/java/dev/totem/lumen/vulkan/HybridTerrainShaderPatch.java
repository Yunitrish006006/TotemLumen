package dev.totem.lumen.vulkan;

/** Adds a separate hit-distance plane, never reusing radiance/history alpha as geometry. */
final class HybridTerrainShaderPatch {
    private HybridTerrainShaderPatch() { }

    static String apply(String source) {
        String anchor = "scene.data[pixelBase + (height - 1u - pixel.y) * width + pixel.x] = color;";
        if (source.indexOf(anchor) < 0 || source.indexOf(anchor) != source.lastIndexOf(anchor)) {
            throw new IllegalStateException("Hybrid terrain output anchor drift");
        }
        return source.replace(anchor, anchor + "\n"
                + "    // RGBA8 transport of raw float bits; zero means no ray-visible surface.\n"
                + "    scene.data[pixelBase + width * height + (height - 1u - pixel.y) * width + pixel.x]\n"
                + "        = floatBitsToUint(primaryHit.hit != 0u ? primaryHit.distance : 0.0);\n");
    }
}
