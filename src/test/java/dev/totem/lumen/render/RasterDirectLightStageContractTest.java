package dev.totem.lumen.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RasterDirectLightStageContractTest {
    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void directLightOwnsOnlyItsOutputAndEmitterPayload() throws Exception {
        String stage = read("src/client/java/dev/totem/lumen/integration/RasterDirectLightStage.java");
        assertTrue(stage.contains("Raster DIRECT_LIGHT radiance and coverage"));
        assertTrue(stage.contains("Raster DIRECT_LIGHT bounded RGB emitters"));
        assertTrue(stage.contains("RasterDirectLights.TEXELS"));
        assertTrue(stage.contains("material.unlitAlbedo()"));
        assertTrue(stage.contains("voxelScene.voxelAtlas()"));
        assertFalse(stage.contains("Raster shared voxel scene atlas"));
        assertFalse(stage.contains("RasterLightingWindow.UPLOADS_PER_FRAME"));
        assertFalse(stage.contains("Section[] uploaded"));
    }

    @Test
    void directLightIsDiagnosticAndCannotReplaceNativeLightingYet() throws Exception {
        String stage = read("src/client/java/dev/totem/lumen/integration/RasterDirectLightStage.java");
        String renderer = read("src/client/java/dev/totem/lumen/integration/RasterLightingRenderer.java");
        String composite = read("src/client/java/dev/totem/lumen/integration/RasterCompositeStage.java");

        assertTrue(stage.contains("totem.lumen.rasterDirectLightStage"));
        assertTrue(stage.contains("compositeConsumer=false"));
        assertTrue(renderer.contains("public static boolean independentLightingActive()"));
        assertTrue(renderer.contains("return false;"));
        assertTrue(renderer.contains("RasterDirectLightStage.record("));
        assertFalse(composite.contains("RasterDirectLightFrame"));
    }

    @Test
    void directFailureIsIsolatedFromIndirectGi() throws Exception {
        String renderer = read("src/client/java/dev/totem/lumen/integration/RasterLightingRenderer.java");
        int directFail = renderer.indexOf("RasterDirectLightStage.fail(directFailure)");
        int indirect = renderer.indexOf("RasterIndirectGiStage.record(");
        assertTrue(directFail >= 0 && indirect > directFail);
        assertTrue(read("src/client/java/dev/totem/lumen/integration/RasterDirectLightStage.java")
                .contains("INDIRECT_GI/COMPOSITE remain available"));
    }

    @Test
    void emitterExtractionIsInactiveUnlessDirectStageWasRequested() throws Exception {
        String scene = read("src/client/java/dev/totem/lumen/integration/RasterLightingScene.java");
        assertTrue(scene.contains("RasterDirectLightStage.requested()"));
        assertTrue(scene.contains("new RasterLightingVolume.Section("));
    }

    @Test
    void directShaderIsCoverageBoundedAndUsesSharedVisibility() throws Exception {
        String shader = read("src/client/resources/assets/totem-lumen/shaders/core/raster_direct_light.fsh");
        assertTrue(shader.contains("float coverage = texture(UnlitAlbedoSampler, texCoord).a"));
        assertTrue(shader.contains("if (coverage <= 0.0) return;"));
        assertTrue(shader.contains("for (int i = 0; i < 64; i++)"));
        assertTrue(shader.contains("for (int i = 0; i < 32; i++)"));
        assertTrue(shader.contains("for (int j = 0; j < 4; j++)"));
        assertTrue(shader.contains("fragColor = vec4(direct, coverage)"));
    }
}
