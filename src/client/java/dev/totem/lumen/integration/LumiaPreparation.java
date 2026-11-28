package dev.totem.lumen.integration;

import com.mojang.blaze3d.pipeline.PipelineCache;
import dev.totem.lumen.render.ProfilePreparation.Status;
import dev.totem.lumen.render.RendererSettings;
import java.util.function.IntSupplier;

public final class LumiaPreparation {
    private static final CachedPipelinePreparation preparation = new CachedPipelinePreparation("Lumia");

    private LumiaPreparation() {}

    public static Status status() { return preparation.status(); }

    public static void invalidate() { preparation.invalidate(); }

    public static void replaceCache(PipelineCache next) { preparation.replaceCache(next); }

    public static void tick() {
        tick(RendererSettings.betaRendering(), LumiaPipelines::currentQualityFlags);
    }

    static void tick(boolean enabled, IntSupplier qualityFlags) {
        preparation.tick(enabled, sources -> LumiaPipelines.preparationVariants(sources, qualityFlags.getAsInt()));
    }
}
