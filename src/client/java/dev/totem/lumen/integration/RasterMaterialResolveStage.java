package dev.totem.lumen.integration;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.geometry.BlockSurfaceSetRegistry;
import dev.totem.lumen.gpu.GpuMaterialPacker;
import dev.totem.lumen.gpu.GpuPbrSurfaceSetScene;
import dev.totem.lumen.gpu.GpuPbrTextureScene;
import dev.totem.lumen.material.MaterialDefinition;
import dev.totem.lumen.render.RasterLightingVolume;
import dev.totem.lumen.render.RasterLightingWindow;
import dev.totem.lumen.render.RasterMaterialRegistry;
import dev.totem.lumen.render.RasterMaterialGpuLayout;
import dev.totem.lumen.render.RasterRawWordTextureLayout;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Optional;

/**
 * Optional MATERIAL_RESOLVE stage used to validate the staged material ABI before independent
 * lighting is allowed to consume it.
 *
 * <p>Material IDs are exact unsigned-16 values. MaterialDefinition records reuse the existing
 * 64-byte GPU ABI and are stored losslessly as raw 32-bit words across RGBA8 texels.</p>
 */
final class RasterMaterialResolveStage {
    static final String PROPERTY = "totem.lumen.rasterMaterialResolve";
    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    private static final int WORDS_PER_MATERIAL = RasterMaterialGpuLayout.WORDS_PER_MATERIAL;
    private static final int LUT_WIDTH = RasterMaterialGpuLayout.LUT_WIDTH;
    private static final int SURFACE_LUT_ROWS =
            RasterRawWordTextureLayout.rowsForWords(GpuPbrSurfaceSetScene.MAX_STORAGE_WORDS);

    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("totem-lumen", "pipeline/raster_material_resolve"))
            .withVertexShader(RenderPipelines.TRACY_BLIT.getShaders().get(ShaderType.VERTEX))
            .withFragmentShader(Identifier.fromNamespaceAndPath("totem-lumen", "core/raster_material_resolve"))
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("NormalSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("MaterialIdAtlas", UniformType.COMBINED_IMAGE_SAMPLER)
                    .build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(
                    Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .build();

    private static final RenderPipeline PROPERTIES_PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("totem-lumen", "pipeline/raster_material_properties"))
            .withVertexShader(RenderPipelines.TRACY_BLIT.getShaders().get(ShaderType.VERTEX))
            .withFragmentShader(Identifier.fromNamespaceAndPath("totem-lumen", "core/raster_material_properties"))
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform("VisibleSurfaceIdentity", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("MaterialLut", UniformType.COMBINED_IMAGE_SAMPLER)
                    .build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(
                    Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .build();

    private static final RenderPipeline UNLIT_ALBEDO_PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("totem-lumen", "pipeline/raster_unlit_cube_albedo"))
            .withVertexShader(RenderPipelines.TRACY_BLIT.getShaders().get(ShaderType.VERTEX))
            .withFragmentShader(Identifier.fromNamespaceAndPath("totem-lumen", "core/raster_unlit_cube_albedo"))
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("NormalSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("VisibleSurfaceIdentity", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("SurfaceSetLut", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("PbrTextureLut", UniformType.COMBINED_IMAGE_SAMPLER)
                    .build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(
                    Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .build();

    private static GpuDevice device;
    private static GpuTexture materialAtlas, visibleIds, baseProperties, unlitAlbedo, materialLut, surfaceSetLut, pbrTextureLut;
    private static GpuTextureView materialAtlasView, visibleIdsView, basePropertiesView, unlitAlbedoView, materialLutView, surfaceSetLutView, pbrTextureLutView;
    private static NativeImage materialTile, lutPixels, surfaceSetPixels, pbrTexturePixels;
    private static ByteBuffer pbrTextureWords;
    private static final RasterLightingVolume.Section[] uploaded =
            new RasterLightingVolume.Section[RasterLightingVolume.SLOTS];
    private static long epoch = -1;
    private static long uploadedMaterialRevision = Long.MIN_VALUE;
    private static long uploadedSurfaceRevision = Long.MIN_VALUE;
    private static long uploadedTextureRevision = Long.MIN_VALUE;
    private static int lutRows, textureRows, uploadedMaterialCount;
    private static long frames, atlasUploadBytes, lutUploadBytes, surfaceLutUploadBytes, textureLutUploadBytes;
    private static boolean logged, failed;

    private RasterMaterialResolveStage() { }

    static boolean enabled() {
        return ENABLED && !failed;
    }

    static void fail(Throwable failure) {
        if (failed) return;
        failed = true;
        TotemLumenClient.LOGGER.error(
                "Raster MATERIAL_RESOLVE disabled for this session; INDIRECT_GI/COMPOSITE remain available",
                failure);
        close();
    }

    static RasterMaterialFrame record(
            CommandEncoder encoder,
            GpuDevice gpu,
            RasterSurfaceFrame surface,
            RasterLightingVolume volume,
            GpuBufferSlice uniforms,
            GpuSampler nearest
    ) {
        if (!enabled()) return null;
        RasterMaterialRegistry.Snapshot materials = RasterLightingScene.materialSnapshot();
        BlockSurfaceSetRegistry.Snapshot surfaces = BlockSurfaceSetRegistry.snapshot();
        ensureTargets(gpu, surface.width(), surface.height(), requiredLutRows(materials));
        uploadLutIfNeeded(encoder, materials);
        uploadSurfaceLutIfNeeded(encoder, surfaces);
        uploadPbrTextureLutIfNeeded(encoder);

        if (epoch != volume.epoch) {
            java.util.Arrays.fill(uploaded, null);
            epoch = volume.epoch;
            encoder.clearColorTexture(materialAtlas, new Vector4f());
        }

        boolean pendingUploads = false;
        int uploadsThisFrame = 0;
        for (int slot = 0; slot < uploaded.length; slot++) {
            RasterLightingVolume.Section section = volume.section(slot);
            if (section == uploaded[slot]) continue;
            if (uploadsThisFrame >= RasterLightingWindow.UPLOADS_PER_FRAME) {
                pendingUploads = true;
                continue;
            }
            for (int i = 0; i < 4096; i++) {
                int materialId = section == null ? 0 : section.materialId(i);
                int surfaceSetId = section == null ? 0 : section.surfaceSetId(i);
                materialTile.setPixelABGR(
                        i & 15,
                        i >>> 4,
                        RasterMaterialGpuLayout.surfaceIdentityTexel(materialId, surfaceSetId));
            }
            encoder.writeToTexture(
                    materialAtlas,
                    materialTile,
                    0, 0,
                    RasterLightingVolume.tileX(slot),
                    RasterLightingVolume.tileY(slot));
            uploaded[slot] = section;
            atlasUploadBytes += 16384;
            uploadsThisFrame++;
        }
        if (pendingUploads) return null;

        var compiled = RenderSystem.getCompiledPipeline(PIPELINE);
        try (var pass = encoder.createRenderPass(
                () -> "Raster MATERIAL_RESOLVE visible IDs", visibleIdsView, Optional.empty())) {
            pass.setPipeline(compiled);
            pass.setUniform("DynamicTransforms", uniforms);
            pass.setUniform("DepthSampler", surface.depth(), nearest);
            pass.setUniform("NormalSampler", surface.normal(), nearest);
            pass.setUniform("MaterialIdAtlas", materialAtlasView, nearest);
            pass.draw(3, 1, 0, 0);
        }

        var propertiesPipeline = RenderSystem.getCompiledPipeline(PROPERTIES_PIPELINE);
        try (var pass = encoder.createRenderPass(
                () -> "Raster MATERIAL_RESOLVE base properties", basePropertiesView, Optional.empty())) {
            pass.setPipeline(propertiesPipeline);
            pass.setUniform("VisibleSurfaceIdentity", visibleIdsView, nearest);
            pass.setUniform("MaterialLut", materialLutView, nearest);
            pass.draw(3, 1, 0, 0);
        }

        var unlitAlbedoPipeline = RenderSystem.getCompiledPipeline(UNLIT_ALBEDO_PIPELINE);
        try (var pass = encoder.createRenderPass(
                () -> "Raster MATERIAL_RESOLVE covered unlit albedo", unlitAlbedoView, Optional.empty())) {
            pass.setPipeline(unlitAlbedoPipeline);
            pass.setUniform("DynamicTransforms", uniforms);
            pass.setUniform("DepthSampler", surface.depth(), nearest);
            pass.setUniform("NormalSampler", surface.normal(), nearest);
            pass.setUniform("VisibleSurfaceIdentity", visibleIdsView, nearest);
            pass.setUniform("SurfaceSetLut", surfaceSetLutView, nearest);
            pass.setUniform("PbrTextureLut", pbrTextureLutView, nearest);
            pass.draw(3, 1, 0, 0);
        }

        frames++;
        if (!logged) {
            logged = true;
            TotemLumenClient.LOGGER.info(
                    "RASTER MATERIAL_RESOLVE ACTIVE: materials={}, revision={}, materialIdBits=16, surfaceSetIdBits=16, baseProperties=RGBA16F(roughness,metallic,opacity,emission), unlitCubeAlbedo=RGBA16F+coverage(staticCanonicalCubesOnly), lutWordEncoding=RGBA8_RAW32, independentLighting=false",
                    materials.entries().size(),
                    materials.revision()
            );
        }
        return new RasterMaterialFrame(
                gpu,
                surface.width(),
                surface.height(),
                surface.frameSerial(),
                materials.revision(),
                materials.entries().size(),
                surfaces.revision(),
                visibleIdsView,
                basePropertiesView,
                unlitAlbedoView,
                materialLutView,
                surfaceSetLutView
        );
    }

    private static int requiredLutRows(RasterMaterialRegistry.Snapshot snapshot) {
        int maxId = snapshot.entries().isEmpty()
                ? 0
                : snapshot.entries().getLast().id();
        return RasterMaterialGpuLayout.requiredRows(maxId);
    }

    private static void ensureTargets(GpuDevice gpu, int width, int height, int requiredRows) {
        boolean surfaceChanged = device != gpu
                || visibleIds == null
                || visibleIds.getWidth(0) != width
                || visibleIds.getHeight(0) != height
                || baseProperties == null
                || baseProperties.getWidth(0) != width
                || baseProperties.getHeight(0) != height
                || unlitAlbedo == null
                || unlitAlbedo.getWidth(0) != width
                || unlitAlbedo.getHeight(0) != height;
        boolean lutChanged = device != gpu || materialLut == null || lutRows != requiredRows;
        if (!surfaceChanged && !lutChanged && materialAtlas != null) return;

        if (device != gpu || materialAtlas == null) {
            close();
            device = gpu;
            materialAtlas = gpu.createTexture(
                    "Raster material ID atlas",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING
                            | GpuTexture.USAGE_RENDER_ATTACHMENT,
                    GpuFormat.RGBA8_UNORM,
                    RasterLightingVolume.ATLAS_WIDTH,
                    RasterLightingVolume.ATLAS_HEIGHT,
                    1, 1);
            materialAtlasView = gpu.createTextureView(materialAtlas);
            materialTile = new NativeImage(16, 256, false);
            surfaceSetLut = gpu.createTexture(
                    "Raster P18 surface-set LUT",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA8_UNORM,
                    RasterRawWordTextureLayout.WIDTH,
                    SURFACE_LUT_ROWS,
                    1, 1);
            surfaceSetLutView = gpu.createTextureView(surfaceSetLut);
            surfaceSetPixels = new NativeImage(
                    RasterRawWordTextureLayout.WIDTH, SURFACE_LUT_ROWS, false);
            uploadedSurfaceRevision = Long.MIN_VALUE;
        }

        if (surfaceChanged) {
            if (visibleIdsView != null) visibleIdsView.close();
            if (basePropertiesView != null) basePropertiesView.close();
            if (unlitAlbedoView != null) unlitAlbedoView.close();
            if (visibleIds != null) visibleIds.close();
            if (baseProperties != null) baseProperties.close();
            if (unlitAlbedo != null) unlitAlbedo.close();
            visibleIds = gpu.createTexture(
                    "Raster visible material IDs",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA8_UNORM,
                    width, height, 1, 1);
            baseProperties = gpu.createTexture(
                    "Raster resolved base material properties",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA16_FLOAT,
                    width, height, 1, 1);
            unlitAlbedo = gpu.createTexture(
                    "Raster covered unlit cube albedo",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA16_FLOAT,
                    width, height, 1, 1);
            visibleIdsView = gpu.createTextureView(visibleIds);
            basePropertiesView = gpu.createTextureView(baseProperties);
            unlitAlbedoView = gpu.createTextureView(unlitAlbedo);
        }

        if (lutChanged) {
            // Material-LUT capacity changes are independent from the P18 surface-set LUT.
            // Do not retire surface-set resources just because the material ID range grew.
            if (materialLutView != null) materialLutView.close();
            if (materialLut != null) materialLut.close();
            if (lutPixels != null) lutPixels.close();
            lutRows = requiredRows;
            materialLut = gpu.createTexture(
                    "Raster material definition LUT",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA8_UNORM,
                    LUT_WIDTH, lutRows, 1, 1);
            materialLutView = gpu.createTextureView(materialLut);
            lutPixels = new NativeImage(LUT_WIDTH, lutRows, false);
            uploadedMaterialRevision = Long.MIN_VALUE;
        uploadedSurfaceRevision = Long.MIN_VALUE;
        }
    }

    private static void uploadLutIfNeeded(
            CommandEncoder encoder,
            RasterMaterialRegistry.Snapshot snapshot
    ) {
        if (uploadedMaterialRevision == snapshot.revision()) return;

        ArrayList<MaterialDefinition> definitions = new ArrayList<>(snapshot.entries().size() + 1);
        definitions.add(MaterialDefinition.AIR);
        int expectedId = 1;
        for (RasterMaterialRegistry.Entry entry : snapshot.entries()) {
            if (entry.id() != expectedId) {
                throw new IllegalStateException(
                        "Raster material registry lost contiguous ID mapping: expected="
                                + expectedId + ", actual=" + entry.id());
            }
            definitions.add(entry.material());
            expectedId++;
        }

        byte[] packed = GpuMaterialPacker.pack(definitions);
        ByteBuffer words = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < lutRows; y++) {
            for (int x = 0; x < LUT_WIDTH; x++) {
                lutPixels.setPixelABGR(x, y, 0);
            }
        }
        for (int id = 0; id < definitions.size(); id++) {
            int y = RasterMaterialGpuLayout.lutY(id);
            for (int word = 0; word < WORDS_PER_MATERIAL; word++) {
                int raw = words.getInt((id * WORDS_PER_MATERIAL + word) * Integer.BYTES);
                // NativeImage's ABGR integer uses low byte=R, matching the shader's byte rebuild.
                lutPixels.setPixelABGR(RasterMaterialGpuLayout.lutX(id, word), y, raw);
            }
        }
        encoder.writeToTexture(materialLut, lutPixels, 0, 0, 0, 0);
        uploadedMaterialRevision = snapshot.revision();
        uploadedMaterialCount = snapshot.entries().size();
        lutUploadBytes += (long) LUT_WIDTH * lutRows * 4;
    }

    private static void uploadSurfaceLutIfNeeded(
            CommandEncoder encoder,
            BlockSurfaceSetRegistry.Snapshot snapshot
    ) {
        if (uploadedSurfaceRevision == snapshot.revision()) return;

        ByteBuffer words = ByteBuffer.allocate(Math.toIntExact(GpuPbrSurfaceSetScene.MAX_STORAGE_BYTES))
                .order(ByteOrder.LITTLE_ENDIAN);
        GpuPbrSurfaceSetScene.pack(words, 0, snapshot);
        int totalPixels = RasterRawWordTextureLayout.WIDTH * SURFACE_LUT_ROWS;
        for (int pixel = 0; pixel < totalPixels; pixel++) {
            int raw = pixel < GpuPbrSurfaceSetScene.MAX_STORAGE_WORDS
                    ? pbrTextureWords.getInt(pixel * Integer.BYTES)
                    : 0;
            surfaceSetPixels.setPixelABGR(
                    RasterRawWordTextureLayout.x(pixel),
                    RasterRawWordTextureLayout.y(pixel),
                    raw);
        }
        encoder.writeToTexture(surfaceSetLut, surfaceSetPixels, 0, 0, 0, 0);
        uploadedSurfaceRevision = snapshot.revision();
        surfaceLutUploadBytes += (long) totalPixels * 4;
    }


    private static void uploadPbrTextureLutIfNeeded(CommandEncoder encoder) {
        long revision = LabPbrTextureRegistry.revision();
        if (uploadedTextureRevision == revision && pbrTextureLut != null) return;

        if (pbrTextureWords == null) {
            pbrTextureWords = ByteBuffer
                    .allocate(Math.toIntExact(GpuPbrTextureScene.MAX_STORAGE_BYTES))
                    .order(ByteOrder.LITTLE_ENDIAN);
        }
        GpuPbrTextureScene.PackResult packed =
                GpuPbrTextureScene.pack(pbrTextureWords, 0, LabPbrTextureRegistry.snapshot());
        int requiredRows = RasterRawWordTextureLayout.rowsForWords(packed.usedWords());

        if (pbrTextureLut == null || textureRows != requiredRows) {
            if (pbrTextureLutView != null) pbrTextureLutView.close();
            if (pbrTextureLut != null) pbrTextureLut.close();
            if (pbrTexturePixels != null) pbrTexturePixels.close();
            textureRows = requiredRows;
            pbrTextureLut = device.createTexture(
                    "Raster P18 texture scene LUT",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA8_UNORM,
                    RasterRawWordTextureLayout.WIDTH,
                    textureRows,
                    1, 1);
            pbrTextureLutView = device.createTextureView(pbrTextureLut);
            pbrTexturePixels = new NativeImage(
                    RasterRawWordTextureLayout.WIDTH, textureRows, false);
        }

        int totalPixels = RasterRawWordTextureLayout.WIDTH * textureRows;
        for (int pixel = 0; pixel < totalPixels; pixel++) {
            int raw = pixel < packed.usedWords()
                    ? words.getInt(pixel * Integer.BYTES)
                    : 0;
            pbrTexturePixels.setPixelABGR(
                    RasterRawWordTextureLayout.x(pixel),
                    RasterRawWordTextureLayout.y(pixel),
                    raw);
        }
        encoder.writeToTexture(pbrTextureLut, pbrTexturePixels, 0, 0, 0, 0);
        uploadedTextureRevision = revision;
        textureLutUploadBytes += (long) totalPixels * 4;
    }

    static void close() {
        if (materialAtlasView != null) materialAtlasView.close();
        if (visibleIdsView != null) visibleIdsView.close();
        if (basePropertiesView != null) basePropertiesView.close();
        if (unlitAlbedoView != null) unlitAlbedoView.close();
        if (materialLutView != null) materialLutView.close();
        if (materialAtlas != null) materialAtlas.close();
        if (visibleIds != null) visibleIds.close();
        if (baseProperties != null) baseProperties.close();
        if (unlitAlbedo != null) unlitAlbedo.close();
        if (materialLut != null) materialLut.close();
        if (materialTile != null) materialTile.close();
        if (lutPixels != null) lutPixels.close();
        if (surfaceSetPixels != null) surfaceSetPixels.close();
        if (pbrTexturePixels != null) pbrTexturePixels.close();
        materialAtlasView = visibleIdsView = basePropertiesView = unlitAlbedoView = materialLutView = surfaceSetLutView = pbrTextureLutView = null;
        materialAtlas = visibleIds = baseProperties = unlitAlbedo = materialLut = surfaceSetLut = pbrTextureLut = null;
        materialTile = lutPixels = surfaceSetPixels = pbrTexturePixels = null;
        pbrTextureWords = null;
        device = null;
        java.util.Arrays.fill(uploaded, null);
        epoch = -1;
        uploadedMaterialRevision = Long.MIN_VALUE;
        uploadedSurfaceRevision = Long.MIN_VALUE;
        uploadedTextureRevision = Long.MIN_VALUE;
        lutRows = textureRows = uploadedMaterialCount = 0;
        if (logged) {
            TotemLumenClient.LOGGER.info(
                    "Raster MATERIAL_RESOLVE resources retired: frames={}, atlasUploadBytes={}, lutUploadBytes={}, surfaceLutUploadBytes={}, textureLutUploadBytes={}",
                    frames, atlasUploadBytes, lutUploadBytes, surfaceLutUploadBytes, textureLutUploadBytes);
        }
        frames = atlasUploadBytes = lutUploadBytes = surfaceLutUploadBytes = textureLutUploadBytes = 0;
        logged = false;
    }
}
