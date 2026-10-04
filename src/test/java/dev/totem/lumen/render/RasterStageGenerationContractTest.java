package dev.totem.lumen.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RasterStageGenerationContractTest {
    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void materialFrameCarriesEveryMaterialInputGeneration() throws Exception {
        String frame = read("src/client/java/dev/totem/lumen/integration/RasterMaterialFrame.java");
        String stage = read("src/client/java/dev/totem/lumen/integration/RasterMaterialResolveStage.java");
        assertTrue(frame.contains("long materialRevision"));
        assertTrue(frame.contains("long surfaceSetRevision"));
        assertTrue(frame.contains("long pbrTextureRevision"));
        assertTrue(stage.contains("uploadedTextureRevision"));
        assertTrue(stage.contains("surfaces.revision()"));
    }

    @Test
    void indirectAndDirectFramesCarrySharedVoxelEpoch() throws Exception {
        String indirectFrame = read("src/client/java/dev/totem/lumen/integration/RasterLightingFrame.java");
        String indirectStage = read("src/client/java/dev/totem/lumen/integration/RasterIndirectGiStage.java");
        String directFrame = read("src/client/java/dev/totem/lumen/integration/RasterDirectLightFrame.java");
        String directStage = read("src/client/java/dev/totem/lumen/integration/RasterDirectLightStage.java");

        assertTrue(indirectFrame.contains("long voxelEpoch"));
        assertTrue(indirectStage.contains("voxelScene.epoch()"));
        assertTrue(directFrame.contains("long voxelEpoch"));
        assertTrue(directStage.contains("voxelScene.epoch()"));
    }

    @Test
    void directFrameBindsToExactMaterialGeneration() throws Exception {
        String frame = read("src/client/java/dev/totem/lumen/integration/RasterDirectLightFrame.java");
        assertTrue(frame.contains("materialRevision == material.materialRevision()"));
        assertTrue(frame.contains("surfaceSetRevision == material.surfaceSetRevision()"));
        assertTrue(frame.contains("pbrTextureRevision == material.pbrTextureRevision()"));
    }

    @Test
    void directCompositeRejectsDifferentVoxelGenerations() throws Exception {
        String composite = read("src/client/java/dev/totem/lumen/integration/RasterDirectCompositeStage.java");
        assertTrue(composite.contains("direct.voxelEpoch() != indirect.voxelEpoch()"));
        assertTrue(composite.contains("mixed frame generations"));
    }

    @Test
    void surfaceSerialSurvivesResourceRetirement() throws Exception {
        String capture = read("src/client/java/dev/totem/lumen/integration/RasterSurfaceCapture.java");
        int close = capture.indexOf("public static void close()");
        assertTrue(close >= 0);
        String closeBody = capture.substring(close);
        assertFalse(closeBody.contains("frameSerial = 0"));
        assertTrue(capture.contains("frameSerial = frameSerial == Long.MAX_VALUE ? 1 : frameSerial + 1"));
    }
}
