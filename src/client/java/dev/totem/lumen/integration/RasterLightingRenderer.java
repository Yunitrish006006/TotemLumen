package dev.totem.lumen.integration;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.render.BackendProbe;
import dev.totem.lumen.render.RasterLightingVolume;
import dev.totem.lumen.render.RendererSettings;
import dev.totem.lumen.render.StagedRenderPlan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Coordinator for the staged raster-primary renderer.
 *
 * <p>GPU resources and shader pipelines belong to their stages. This class only validates the
 * frame, creates shared frame uniforms, records the stage sequence and owns fail-closed readiness.</p>
 */
public final class RasterLightingRenderer {
    private static boolean failed, ready, logged;

    private RasterLightingRenderer() { }

    public static boolean ready() {
        return ready && !failed && RendererSettings.rasterLightingEnabled();
    }

    public static boolean unavailable() {
        return failed;
    }

    /**
     * A coverage-aware direct composite may run as an opt-in diagnostic, but material coverage is
     * partial and sun/sky are absent. This is not full independent-lighting readiness.
     */
    public static boolean independentLightingActive() {
        return false;
    }

    private static StagedRenderPlan stagePlan() {
        if (RasterDirectCompositeStage.enabled()) {
            return StagedRenderPlan.rasterDirectLightCompositePath();
        }
        if (RasterDirectLightStage.enabled()) {
            return StagedRenderPlan.rasterDirectLightDiagnosticPath();
        }
        return RasterMaterialResolveStage.enabled()
                ? StagedRenderPlan.rasterMaterialMetadataPath()
                : StagedRenderPlan.rasterOwnedSurfacePath();
    }

    public static void tickLifecycle(Minecraft client) {
        if (!RendererSettings.rasterLightingEnabled() || client.level == null) close();
    }

    public static void render(CameraRenderState camera, RasterSurfaceFrame surface) {
        if (!RendererSettings.rasterLightingEnabled() || failed || surface == null) return;
        ready = false;

        // The current staged path is explicitly native-lit surface -> indirect correction.
        // Never silently treat a future unlit material frame as if DIRECT_LIGHT already existed.
        if (surface.supportsIndependentLighting()) return;

        var volume = RasterLightingScene.snapshot();
        if (camera == null || !camera.initialized || volume == null) return;
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (target == null || target.getColorTexture() == null || target.getColorTextureView() == null) return;
        if (!BackendProbe.detect().vulkan()) return;

        try {
            var gpu = RenderSystem.getDevice();
            int width = target.getColorTexture().getWidth(0);
            int height = target.getColorTexture().getHeight(0);
            if (!surface.matches(gpu, width, height)) return;

            Matrix4f inverse = new Matrix4f(camera.projectionMatrix)
                    .mul(camera.viewRotationMatrix)
                    .invert();
            Matrix4f metadata = new Matrix4f().zero()
                    .setColumn(0, new Vector4f(
                            RasterLightingVolume.RAY_DISTANCE,
                            RendererSettings.giQuality().samples(),
                            gpu.getDeviceInfo().isZZeroToOne() ? 1 : 0,
                            ClientLightingWorldRules.tuning().brightnessMultiplier()))
                    .setColumn(1, new Vector4f(0, 0, 0, 0));
            Vector3f offset = new Vector3f(
                    (float) (camera.pos.x - volume.x),
                    (float) (camera.pos.y - volume.y),
                    (float) (camera.pos.z - volume.z));
            var uniforms = RenderSystem.getDynamicUniforms()
                    .writeTransform(inverse, new Vector4f(1), offset, metadata);
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

            var encoder = gpu.createCommandEncoder();
            RasterVoxelSceneFrame voxelScene = RasterVoxelSceneGpu.prepare(encoder, gpu, volume);
            if (!voxelScene.coherent()) {
                // Shared atlas uploads were recorded, but no lighting stage may sample stale slots.
                encoder.submit();
                return;
            }

            RasterMaterialFrame material = null;
            if (RasterMaterialResolveStage.enabled()) {
                try {
                    material = RasterMaterialResolveStage.record(
                            encoder, gpu, surface, volume, uniforms, nearest);
                } catch (RuntimeException materialFailure) {
                    RasterMaterialResolveStage.fail(materialFailure);
                }
            }

            RasterDirectLightFrame direct = null;
            if (material != null && RasterDirectLightStage.enabled()) {
                try {
                    direct = RasterDirectLightStage.record(
                            encoder,
                            gpu,
                            surface,
                            material,
                            voxelScene,
                            volume,
                            uniforms,
                            nearest,
                            offset.x,
                            offset.y,
                            offset.z);
                } catch (RuntimeException directFailure) {
                    RasterDirectLightStage.fail(directFailure);
                }
            }
            RasterLightingFrame lighting = RasterIndirectGiStage.record(
                    encoder, gpu, surface, voxelScene, uniforms, nearest);
            if (lighting == null) {
                // Atlas uploads were recorded but this generation is not coherent enough to shade.
                encoder.submit();
                return;
            }

            if (RasterDirectCompositeStage.enabled() && material != null && direct != null) {
                RasterDirectCompositeStage.record(
                        encoder,
                        target.getColorTextureView(),
                        uniforms,
                        nearest,
                        surface,
                        material,
                        lighting,
                        direct);
            } else {
                RasterCompositeStage.record(
                        encoder,
                        target.getColorTextureView(),
                        uniforms,
                        nearest,
                        surface,
                        lighting);
            }
            encoder.submit();

            ready = lighting.completeScene();
            if (ready && !logged) {
                logged = true;
                TotemLumenClient.LOGGER.info(
                        "RASTER staged renderer ACTIVE: stages={}, surfaceSemantic={}, primaryRays=0",
                        stagePlan().stages(),
                        surface.colorSemantic()
                );
            }
        } catch (RuntimeException failure) {
            failed = true;
            ready = false;
            TotemLumenClient.LOGGER.error(
                    "Raster staged renderer disabled; retaining Minecraft rendering", failure);
            close();
        }
    }

    public static void close() {
        ready = false;
        RasterDirectCompositeStage.close();
        RasterDirectLightStage.close();
        RasterMaterialResolveStage.close();
        RasterIndirectGiStage.close();
        RasterVoxelSceneGpu.close();
        logged = false;
    }
}
