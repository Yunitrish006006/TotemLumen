package dev.totem.lumen.render;

import dev.totem.lumen.integration.LumiaPreparation;
import dev.totem.lumen.integration.RasterLightingRenderer;
import dev.totem.lumen.render.RendererSettings.RenderProfile;

/** Shader readiness is independent of active-world resources and the saved profile preference. */
public final class ProfilePreparation {
    public enum Status { IDLE, PREPARING, READY, FAILED }

    private ProfilePreparation() {}

    public static Status status(RenderProfile profile) {
        return switch (profile) {
            case MINECRAFT_PURE, MINECRAFT_RGB -> Status.READY;
            case TOTEM_LUMEN -> RendererBootstrap.preparationStatus();
            case RASTER_RAY -> RasterLightingRenderer.preparationStatus();
            case LUMIA -> LumiaPreparation.status();
        };
    }

    public static boolean ready(RenderProfile profile) {
        return status(profile) == Status.READY;
    }
}
