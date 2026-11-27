package dev.totem.lumen.integration;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.util.Map;
import net.minecraft.resources.Identifier;

/** Selects compatible world-space feature pipelines, including their native OIT accumulation variants. */
public final class LumiaActors {
    public static final int EMISSION_FLAG = 256;
    private static final Map<Identifier, Identifier> SHADERS = Map.of(
            vanilla("entity"), lumia("entity"),
            vanilla("item"), lumia("item"),
            vanilla("block"), lumia("block"),
            vanilla("particle"), lumia("particle"),
            vanilla("text"), lumia("text"),
            vanilla("clouds"), lumia("clouds"),
            vanilla("rendertype_leash"), lumia("leash"));
    private static final Identifier SHADOW = vanilla("rendertype_entity_shadow");
    private static boolean worldDraw;

    private LumiaActors() {}

    private static Identifier vanilla(String name) {
        return Identifier.fromNamespaceAndPath("minecraft", "core/" + name);
    }

    private static Identifier lumia(String name) {
        return Identifier.fromNamespaceAndPath("totem-lumen", "core/lumia_" + name);
    }

    public static boolean setWorldDraw(boolean enabled) {
        boolean previous = worldDraw;
        worldDraw = enabled;
        return previous;
    }

    public static boolean active() {
        return worldDraw && LumiaScene.ready();
    }

    public static boolean nativeShadow(RenderPipeline source) {
        return SHADOW.equals(source.getShaders().get(ShaderType.FRAGMENT));
    }

    public static RenderPipeline variant(RenderPipeline source) {
        var shader = source.getShaders().get(ShaderType.VERTEX);
        var replacement = SHADERS.get(shader);
        var flags = source.getShaderDefines().flags();
        if (replacement == null || !shader.equals(source.getShaders().get(ShaderType.FRAGMENT))
                || flags.contains("IS_GUI") || flags.contains("IS_SEE_THROUGH")
                || flags.contains("EMISSIVE") || flags.contains("OIT_ALPHA_ONLY")) return source;
        return LumiaPipelines.variant(source, replacement, false);
    }
}
