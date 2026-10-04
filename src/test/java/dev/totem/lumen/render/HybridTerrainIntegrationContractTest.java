package dev.totem.lumen.render;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Source-boundary assertions complement shader compilation; not visual acceptance evidence. */
class HybridTerrainIntegrationContractTest {
    private static String read(String file) throws Exception { return Files.readString(Path.of(file)); }

    @Test void retainsVanillaWorldAndCompositesOutsideTheHud() throws Exception {
        String mixin = read("src/client/java/dev/totem/lumen/mixin/LevelRendererTakeoverMixin.java");
        assertTrue(mixin.contains("if (HybridTerrainPolicy.ENABLED || !P5StableLookupRenderer.readyForWorldTakeover())"));
        assertTrue(mixin.contains("@Inject(method = \"render\", at = @At(\"RETURN\"))"));
        assertTrue(mixin.contains("P5StableLookupRenderer.presentHybridTerrain(cameraState)"));
    }

    @Test void compositorRejectsSkyMissesDisocclusionAndDistanceBoundary() throws Exception {
        String shader = read("src/client/resources/assets/totem-lumen/shaders/core/hybrid_terrain.fsh");
        assertTrue(shader.contains("depth <= 0.000001) discard"));
        assertTrue(shader.contains("tracedDistance <= 0.0) discard"));
        assertTrue(shader.contains("currentDistance >= limit) discard"));
        assertTrue(shader.contains("abs(tracedDistance - length(previousPosition)) > tolerance) discard"));
        assertTrue(shader.contains("smoothstep(limit * ColorModulator.x, limit"));
        assertTrue(shader.contains("texelFetch(TotemSampler"));
    }

    @Test void packedDistanceSurvivesRgba8TransportIncludingMiss() {
        for (float distance : new float[]{0, 0.001f, 1, 31.999f, 32, 64, 128}) {
            int bits = Float.floatToRawIntBits(distance);
            int decoded = 0;
            for (int channel = 0; channel < 4; channel++) {
                float normalizedByte = ((bits >>> (channel * 8)) & 255) / 255f;
                decoded |= Math.round(normalizedByte * 255f) << (channel * 8);
            }
            assertEquals(distance, Float.intBitsToFloat(decoded));
        }
    }

    @Test void sceneTailCapacityIncludesTheDepthPlane() throws Exception {
        String renderer = read("src/client/java/dev/totem/lumen/vulkan/P5StableLookupRenderer.java");
        assertTrue(renderer.contains("putWord(buffer, 52, HybridTerrainPolicy.outputRows(resources.height, HybridTerrainPolicy.ENABLED))"));
        assertTrue(renderer.contains("int totalWords = Math.addExact(pixelBaseWord, outputWords)"));
        for (boolean hybrid : new boolean[]{false, true}) {
            int width = 480, height = 270, pixelBase = 100;
            int tail = pixelBase + width * HybridTerrainPolicy.outputRows(height, hybrid);
            int lastOutput = pixelBase + width * height * (hybrid ? 2 : 1) - 1;
            assertEquals(lastOutput + 1, tail);
        }
    }
}
