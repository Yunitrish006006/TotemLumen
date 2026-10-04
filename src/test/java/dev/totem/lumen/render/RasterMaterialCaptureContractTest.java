package dev.totem.lumen.render;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Source isolation guards supplement the client ABI/shader and native runtime checks. */
class RasterMaterialCaptureContractTest {
    @Test void reducedResolutionDepthNeighboursAddressDistinctTexelCenters() {
        // CPU reference for the shader's centre alignment, including non-integer scale ratios.
        for (int size : new int[]{540, 576, 960, 1024, 1377, 2160, 4096}) {
            for (int reduced : new int[]{Math.max(1, size / 2), 288, 512}) {
                float pixel = 1f / size;
                for (int i = 0; i < reduced; i++) {
                    float uv = (i + 0.5f) / reduced;
                    int cell = (int) Math.floor(uv / pixel);
                    if (cell < 1 || cell >= size - 1) continue;
                    float center = (cell + 0.5f) * pixel;
                    assertEquals(cell, (int) Math.floor(center * size));
                    assertEquals(cell - 1, (int) Math.floor((center - pixel) * size));
                    assertEquals(cell + 1, (int) Math.floor((center + pixel) * size));
                }
            }
        }
    }

    @Test void materialShadersDoNotSampleVanillaLightOrCompositeLitColour() throws Exception {
        var root = Path.of("src/client/resources/assets/totem-lumen/shaders/core");
        String vertex = Files.readString(root.resolve("raster_material.vsh"));
        String fragment = Files.readString(root.resolve("raster_material.fsh"));
        assertFalse(vertex.contains("sample_lightmap"));
        assertFalse(vertex.contains("UV2"));
        assertTrue(vertex.contains("materialColor = Color;"));
        assertTrue(fragment.contains("if (base.a < ALPHA_CUTOUT) discard;"));
        assertFalse(fragment.contains("apply_fog"));
        assertFalse(fragment.contains("SceneSampler"));
        assertTrue(fragment.contains("fragColor = vec4(base.rgb, 1.0)"));
    }

    @Test void captureIsOptInProfileGatedAndNeverEditsGameplayOrMeshes() throws Exception {
        String source = Files.readString(Path.of("src/client/java/dev/totem/lumen/integration/RasterMaterialCapture.java"));
        assertTrue(source.contains("Boolean.getBoolean(\"totem.lumen.rasterMaterialCapture\")"));
        assertTrue(source.contains("ENABLED && !failed && RendererSettings.rasterLightingEnabled()"));
        assertTrue(source.contains("finally { clearPending(); }"));
        assertTrue(source.contains("if (rawPreviewRequested()) encoder.copyTextureToTexture"));
        assertTrue(source.contains("ChunkSectionLayerGroup.OPAQUE.layers()"));
        assertTrue(source.contains("(long) w * h > MAX_PIXELS"));
        assertFalse(source.contains("ClientGameplayLightPredictor"));
        assertFalse(source.contains("setBlock("));
    }

    @Test void rgbPreviewRequiresSameFrameCaptureAndNeverReadsNativeLitColour() throws Exception {
        var root = Path.of("src/client/java/dev/totem/lumen");
        String capture = Files.readString(root.resolve("integration/RasterMaterialCapture.java"));
        String renderer = Files.readString(root.resolve("integration/RasterLightingRenderer.java"));
        String hook = Files.readString(root.resolve("mixin/LevelRendererTakeoverMixin.java"));
        assertTrue(capture.contains("ENABLED && RGB_PREVIEW"));
        assertTrue(capture.contains("ENABLED && PREVIEW && !RGB_PREVIEW"));
        assertTrue(capture.contains("capturedFrameSerial = -1;"));
        assertTrue(capture.contains("capturedFrameSerial != frameSerial"));
        assertTrue(capture.contains("capturedFrameSerial = frameSerial;"));
        assertTrue(capture.contains("return new RasterSurfaceFrame("));
        assertTrue(capture.contains("private static void retireTargets() {\n        capturedThisFrame = false;"));
        assertTrue(renderer.contains("if (materialPreview && material == null) return;"));
        assertTrue(renderer.contains("if (!materialPreview) encoder.copyTextureToTexture"));
        assertTrue(renderer.contains("materialPreview ? material.baseColor() : sceneView"));
        assertTrue(renderer.contains("StagedRenderPlan.rasterMaterialPreview()"));
        assertEquals(2, renderer.split("materialPreview \\? material.depth\\(\\) : target.getDepthTextureView\\(\\)", -1).length - 1);
        assertTrue(hook.indexOf("RasterMaterialCapture.render();") < hook.indexOf("RasterLightingRenderer.render(cameraState);"));
    }

    @Test void previewRetainsFarSurfacesAndHasBoundedExplicitlyApproximateLighting() throws Exception {
        var root = Path.of("src/client/resources/assets/totem-lumen/shaders/core");
        String light = Files.readString(root.resolve("raster_ray.fsh"));
        String composite = Files.readString(root.resolve("raster_ray_composite.fsh"));
        assertTrue(light.contains("for (int i = 0; i < 64; i++)"));
        assertTrue(light.contains("for (int i = 0; i < 3; i++)"));
        assertTrue(light.contains("1.0 - travelled / TextureMat[0].x"));
        assertTrue(light.contains("p + ModelOffset - normal * 0.08"));
        assertTrue(light.contains("(floor(texCoord / pixel) + 0.5) * pixel"));
        assertTrue(light.contains("positionAt(surfaceUv, depth)"));
        assertFalse(light.contains("positionAt(texCoord"));
        String preview = composite.substring(composite.indexOf("if (TextureMat[1].x > 0.5)"), composite.indexOf("if (depth <= 0.000001) discard"));
        assertFalse(preview.contains("discard"));
        assertTrue(preview.contains("vec3 illumination = vec3(0.08)"));
        assertTrue(preview.contains("base.rgb * illumination"));
        assertTrue(composite.contains("min(abs(center - a), abs(b - center))"));
        assertTrue(preview.contains("0.5 * dot(vec2(slopeX, slopeY), footprint)"));
    }
}
