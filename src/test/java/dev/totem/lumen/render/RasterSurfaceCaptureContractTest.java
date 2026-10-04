package dev.totem.lumen.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RasterSurfaceCaptureContractTest {
    private static String read(String relative) throws Exception {
        return Files.readString(Path.of(relative));
    }

    @Test
    void ownedCaptureNeverReplaysChunkDrawBuffers() throws Exception {
        String capture = read("src/client/java/dev/totem/lumen/integration/RasterSurfaceCapture.java");
        assertTrue(capture.contains("encoder.copyTextureToTexture(nativeColor, color"));
        assertTrue(capture.contains("Totem raster owned surface depth"));
        assertTrue(capture.contains("GpuFormat.R32_FLOAT"));
        assertFalse(capture.contains("ChunkSectionsToRender"));
        assertFalse(capture.contains("renderLayers"));
        assertFalse(capture.contains("GpuBufferSlice"));
        assertFalse(capture.contains("RasterMaterialLayersInvoker"));
    }

    @Test
    void depthCaptureCopiesNativeDepthIntoOwnedColorTexture() throws Exception {
        String shader = read("src/client/resources/assets/totem-lumen/shaders/core/raster_surface_depth.fsh");
        assertTrue(shader.contains("uniform sampler2D DepthSampler"));
        assertTrue(shader.contains("float depth = texture(DepthSampler, texCoord).r"));
        assertTrue(shader.contains("fragColor = vec4(depth"));
    }

    @Test
    void levelRendererPublishesOwnedSurfaceBeforeLighting() throws Exception {
        String hook = read("src/client/java/dev/totem/lumen/mixin/LevelRendererTakeoverMixin.java");
        int capture = hook.indexOf("RasterSurfaceCapture.capture()");
        int lighting = hook.indexOf("RasterLightingRenderer.render(cameraState, surface)");
        assertTrue(capture >= 0 && lighting > capture);
        assertFalse(hook.contains("RasterMaterialCapture"));
    }

    @Test
    void stagesConsumeOnlyExplicitFrameContractsForSceneInputs() throws Exception {
        String renderer = read("src/client/java/dev/totem/lumen/integration/RasterLightingRenderer.java");
        String indirect = read("src/client/java/dev/totem/lumen/integration/RasterIndirectGiStage.java");
        String composite = read("src/client/java/dev/totem/lumen/integration/RasterCompositeStage.java");
        assertTrue(renderer.contains("render(CameraRenderState camera, RasterSurfaceFrame surface)"));
        assertTrue(renderer.contains("RasterIndirectGiStage.record("));
        assertTrue(renderer.contains("RasterCompositeStage.record("));
        assertTrue(indirect.contains("surface.depth()"));
        assertTrue(composite.contains("surface.depth()"));
        assertTrue(composite.contains("surface.baseColor()"));
        assertFalse(indirect.contains("target.getDepthTextureView()"));
        assertFalse(composite.contains("target.getDepthTextureView()"));
        assertFalse(renderer.contains("RasterMaterialCapture"));
    }

    @Test
    void borrowedReplayMixinsAreNotInstalled() throws Exception {
        String mixins = read("src/main/resources/totem-lumen.client.mixins.json");
        assertFalse(mixins.contains("RasterMaterialCaptureMixin"));
        assertFalse(mixins.contains("RasterMaterialLayersInvoker"));
    }

    @Test
    void nativeLitSurfaceCannotPretendToBeIndependentMaterial() throws Exception {
        String frame = read("src/client/java/dev/totem/lumen/integration/RasterSurfaceFrame.java");
        String capture = read("src/client/java/dev/totem/lumen/integration/RasterSurfaceCapture.java");
        assertTrue(frame.contains("NATIVE_LIT_COLOR"));
        assertTrue(frame.contains("UNLIT_MATERIAL_COLOR"));
        assertTrue(frame.contains("supportsIndependentLighting()"));
        assertTrue(capture.contains("RasterSurfaceFrame.ColorSemantic.NATIVE_LIT_COLOR"));
        assertFalse(capture.contains("UNLIT_MATERIAL_COLOR"));
    }
}
