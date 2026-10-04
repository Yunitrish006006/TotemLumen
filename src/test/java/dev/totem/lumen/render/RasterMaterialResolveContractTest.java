package dev.totem.lumen.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RasterMaterialResolveContractTest {
    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void stageUsesExistingMaterialDefinitionAbiWithoutFloatQuantization() throws Exception {
        String stage = read("src/client/java/dev/totem/lumen/integration/RasterMaterialResolveStage.java");
        assertTrue(stage.contains("GpuMaterialPacker.pack(definitions)"));
        assertTrue(stage.contains("ByteOrder.LITTLE_ENDIAN"));
        assertTrue(stage.contains("RasterMaterialGpuLayout.surfaceIdentityTexel(materialId, surfaceSetId)"));
        assertTrue(stage.contains("lutPixels.setPixelABGR(RasterMaterialGpuLayout.lutX(id, word), y, raw)"));
        assertFalse(stage.contains("Math.round(material.roughness() * 255"));
        assertFalse(stage.contains("Math.round(material.metallic() * 255"));
    }

    @Test
    void resolveUsesOwnedSurfaceAndVoxelMaterialIdsOnly() throws Exception {
        String stage = read("src/client/java/dev/totem/lumen/integration/RasterMaterialResolveStage.java");
        String shader = read("src/client/resources/assets/totem-lumen/shaders/core/raster_material_resolve.fsh");
        assertTrue(stage.contains("RasterLightingScene.materialSnapshot()"));
        assertTrue(stage.contains("section.materialId(i)"));
        assertTrue(stage.contains("section.surfaceSetId(i)"));
        assertTrue(stage.contains("surface.depth()"));
        assertTrue(stage.contains("surface.normal()"));
        assertTrue(shader.contains("uniform sampler2D MaterialIdAtlas"));
        assertTrue(shader.contains("vec4 surfaceIdentity(ivec3 cell)"));
        assertTrue(shader.contains("p + ModelOffset - normal * 0.08"));
        assertFalse(stage.contains("ChunkSectionsToRender"));
        assertFalse(stage.contains("getBlockState"));
    }

    @Test
    void materialStageIsOptInAndCannotEnableIndependentLightingYet() throws Exception {
        String stage = read("src/client/java/dev/totem/lumen/integration/RasterMaterialResolveStage.java");
        String renderer = read("src/client/java/dev/totem/lumen/integration/RasterLightingRenderer.java");
        assertTrue(stage.contains("totem.lumen.rasterMaterialResolve"));
        assertTrue(stage.contains("unlitAlbedo=false"));
        assertTrue(renderer.contains("public static boolean independentLightingActive()"));
        assertTrue(renderer.contains("return false;"));
        assertFalse(renderer.contains("LumenRenderStage.DIRECT_LIGHT"));
    }

    @Test
    void materialFailureDoesNotRetireIndirectGi() throws Exception {
        String stage = read("src/client/java/dev/totem/lumen/integration/RasterMaterialResolveStage.java");
        String renderer = read("src/client/java/dev/totem/lumen/integration/RasterLightingRenderer.java");
        assertTrue(stage.contains("MATERIAL_RESOLVE disabled for this session; INDIRECT_GI/COMPOSITE remain available"));
        int materialCatch = renderer.indexOf("RasterMaterialResolveStage.fail(materialFailure)");
        int indirect = renderer.indexOf("RasterIndirectGiStage.record(");
        assertTrue(materialCatch >= 0 && indirect > materialCatch);
    }

    @Test
    void materialLutResizeDoesNotRetireIndependentSurfaceSetLut() throws Exception {
        String stage = read("src/client/java/dev/totem/lumen/integration/RasterMaterialResolveStage.java");
        int resize = stage.indexOf("if (lutChanged) {");
        int upload = stage.indexOf("private static void uploadLutIfNeeded");
        assertTrue(resize >= 0 && upload > resize);
        String resizeBody = stage.substring(resize, upload);
        assertTrue(resizeBody.contains("materialLutView.close()"));
        assertTrue(resizeBody.contains("materialLut.close()"));
        assertFalse(resizeBody.contains("surfaceSetLutView.close()"));
        assertFalse(resizeBody.contains("surfaceSetLut.close()"));
        assertTrue(stage.contains("uploadedSurfaceRevision = Long.MIN_VALUE;"));
    }

    @Test
    void materialResolvePublishesDecodedBasePropertyPlane() throws Exception {
        String stage = read("src/client/java/dev/totem/lumen/integration/RasterMaterialResolveStage.java");
        String frame = read("src/client/java/dev/totem/lumen/integration/RasterMaterialFrame.java");
        String shader = read("src/client/resources/assets/totem-lumen/shaders/core/raster_material_properties.fsh");
        assertTrue(stage.contains("raster_material_properties"));
        assertTrue(stage.contains("Raster resolved base material properties"));
        assertTrue(stage.contains("GpuFormat.RGBA16_FLOAT"));
        assertTrue(stage.contains("basePropertiesView"));
        assertTrue(frame.contains("GpuTextureView baseProperties"));
        assertTrue(shader.contains("rasterMaterialFloat(MaterialLut, materialId, 2u)"));
        assertTrue(shader.contains("rasterMaterialFloat(MaterialLut, materialId, 3u)"));
        assertTrue(shader.contains("rasterMaterialFloat(MaterialLut, materialId, 4u)"));
        assertTrue(shader.contains("rasterMaterialWord(MaterialLut, materialId, 1u)"));
        assertTrue(shader.contains("fragColor = vec4(roughness, metallic, opacity, emission)"));
        assertTrue(shader.contains("unlit albedo"));
    }
}
