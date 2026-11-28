package dev.totem.lumen.integration;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.render.RendererSettings;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.CardinalLighting;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;

/** Retains the native draw ABI, including blend targets, OIT defines, depth and instancing. */
public final class LumiaPipelines extends RenderPipeline {
    public static final CardinalLighting NEUTRAL_CARDINAL = new CardinalLighting(1, 1, 1, 1, 1, 1);
    private static final Identifier TERRAIN = Identifier.withDefaultNamespace("core/terrain");
    private static final Identifier LUMIA_TERRAIN = Identifier.fromNamespaceAndPath("totem-lumen", "core/lumia_terrain");

    private static final Identifier NATIVE_LIGHT = Identifier.fromNamespaceAndPath("totem-lumen", "core/lumia_native_light");
    private static final ShaderDefines MASK_LIGHT = ShaderDefines.builder().define("LUMIA_NATIVE_LIGHT").build();

    private static final int LOW_DAYLIGHT = 1;
    private static final int NO_LOCAL_LIGHTS = 2;
    private static final int NO_REFLECTIONS = 4;

    static int qualityFlags(RendererSettings.Quality shadows, RendererSettings.LocalLightQuality localLights,
                            boolean reflections) {
        return (shadows == RendererSettings.Quality.LOW ? LOW_DAYLIGHT : 0)
                | (localLights == RendererSettings.LocalLightQuality.OFF ? NO_LOCAL_LIGHTS : 0)
                | (reflections ? 0 : NO_REFLECTIONS);
    }

    static int currentQualityFlags() {
        return qualityFlags(RendererSettings.shadowQuality(), RendererSettings.localLightQuality(),
                RendererSettings.reflectionsEnabled());
    }

    private record Key(RenderPipeline source, Identifier shaders, boolean translucent, int quality) {}
    private static final Map<Key, RenderPipeline> VARIANTS = new ConcurrentHashMap<>();
    private static final ShaderDefines TRANSLUCENT = ShaderDefines.builder().define("LUMIA_TRANSLUCENT").build();

    private static boolean nativeTerrain(RenderPipeline source) {
        return source.getPolygonMode() != PolygonMode.WIREFRAME && TERRAIN.equals(source.getShaders().get(ShaderType.VERTEX))
                && TERRAIN.equals(source.getShaders().get(ShaderType.FRAGMENT));
    }

    /** ShaderManager owns compilation, finalization and replacement of the device pipeline cache. */
    public static List<RenderPipeline> optionalForReload(List<RenderPipeline> optional) {
        if (!RendererSettings.lumiaEnabled()) return optional;
        var sources = new LinkedHashSet<>(RenderPipelines.requiredPipelines());
        sources.addAll(optional);
        var combined = new LinkedHashSet<>(optional);
        int quality = currentQualityFlags();
        for (var source : sources) {
            var variant = drawVariant(source, quality);
            if (variant == source) continue;
            combined.add(variant);
            if (variant.getShaderDefines().flags().contains("LUMIA_TRANSLUCENT")) {
                var fallback = nativeGlassFallback(source);
                if (fallback != source) combined.add(fallback);
            }
        }
        TotemLumenClient.LOGGER.info("Preparing {} Lumia pipelines during shader reload", combined.size() - optional.size());
        return List.copyOf(combined);
    }

    static RenderPipeline drawVariant(RenderPipeline source, int quality) {
        if (nativeTerrain(source)) {
            for (var layer : ChunkSectionLayer.values()) {
                if (source == layer.pipeline(false) || source == layer.pipeline(true))
                    return terrainVariant(source, layer.translucent(), quality);
            }
            // OIT overrides the regular translucent terrain draw's pipeline.
            var flags = source.getShaderDefines().flags();
            if (flags.contains("OIT_ALPHA_ONLY") || flags.contains("OIT_ACCUMULATE"))
                return terrainVariant(source, true, quality);
            return source;
        }
        return LumiaActors.variant(source, quality);
    }

    public static RenderPipeline nativeGlassFallback(RenderPipeline source) {
        if (!nativeTerrain(source) || source.getShaderDefines().flags().contains("OIT_ALPHA_ONLY")) return source;
        return variant(source, NATIVE_LIGHT, false, 0);
    }

    public static RenderPipeline terrainVariant(RenderPipeline source, boolean translucent) {
        return terrainVariant(source, translucent, currentQualityFlags());
    }

    static RenderPipeline terrainVariant(RenderPipeline source, boolean translucent, int quality) {
        // OIT depth/transmittance and accumulation must use identical glass coverage.
        if (!nativeTerrain(source)) return source;
        return variant(source, LUMIA_TERRAIN, translucent, quality);
    }

    public static RenderPipeline variant(RenderPipeline source, Identifier shaders, boolean translucent) {
        return variant(source, shaders, translucent, currentQualityFlags());
    }

    static RenderPipeline variant(RenderPipeline source, Identifier shaders, boolean translucent, int quality) {
        return VARIANTS.computeIfAbsent(new Key(source, shaders, translucent, quality),
                key -> new LumiaPipelines(key.source(), key.shaders(), key.translucent(), key.quality()));
    }

    private static ShaderDefines defines(RenderPipeline source, Identifier shaders, boolean translucent, int quality) {
        var nativeDefines = source.getShaderDefines();
        if (shaders.equals(NATIVE_LIGHT)) return nativeDefines.withOverrides(MASK_LIGHT);
        var defines = translucent ? nativeDefines.withOverrides(TRANSLUCENT) : nativeDefines;
        if (quality == 0) return defines;
        var flags = ShaderDefines.builder();
        if ((quality & LOW_DAYLIGHT) != 0) flags.define("LUMIA_LOW_DAYLIGHT");
        if ((quality & NO_LOCAL_LIGHTS) != 0) flags.define("LUMIA_NO_LOCAL_LIGHTS");
        if ((quality & NO_REFLECTIONS) != 0) flags.define("LUMIA_NO_REFLECTIONS");
        return defines.withOverrides(flags.build());
    }

    private LumiaPipelines(RenderPipeline source, Identifier shaders, boolean translucent, int quality) {
        super(Identifier.fromNamespaceAndPath("totem-lumen", "lumia/" + shaders.getPath() + "/"
                        + source.getLocation().getNamespace() + "/" + source.getLocation().getPath()
                        + (translucent ? "/translucent" : "/solid") + (quality == 0 ? "" : "/q" + quality)),
                shaders.equals(NATIVE_LIGHT) ? Map.of(ShaderType.VERTEX, LUMIA_TERRAIN, ShaderType.FRAGMENT, TERRAIN)
                        : Map.of(ShaderType.VERTEX, shaders, ShaderType.FRAGMENT, shaders),
                defines(source, shaders, translucent, quality),
                layouts(source, shaders, translucent), source.getColorTargetStates().toArray(ColorTargetState[]::new),
                source.getDepthStencilState(), source.getPolygonMode(), source.isCull(),
                source.getVertexFormatBindings().toArray(VertexFormat[]::new),
                source.getPrimitiveTopology(), source.pushConstantSize(), source.getSortKey());
    }

    private static List<BindGroupLayout> layouts(RenderPipeline source, Identifier shaders, boolean translucent) {
        if (shaders.equals(NATIVE_LIGHT)) return source.getBindGroupLayouts();
        var layouts = new ArrayList<>(source.getBindGroupLayouts());
        layouts.add(LumiaScene.layout());
        if (translucent && shaders.equals(LUMIA_TERRAIN) && !source.getShaderDefines().flags().contains("OIT_ALPHA_ONLY")) {
            layouts.add(LumiaGlassBackground.layout());
        }
        return layouts;
    }
}
