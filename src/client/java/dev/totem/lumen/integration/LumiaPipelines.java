package dev.totem.lumen.integration;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.CardinalLighting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Retains the native draw ABI, including blend targets, OIT defines, depth and instancing. */
public final class LumiaPipelines extends RenderPipeline {
    public static final CardinalLighting NEUTRAL_CARDINAL = new CardinalLighting(1, 1, 1, 1, 1, 1);
    private static final Identifier TERRAIN = Identifier.withDefaultNamespace("core/terrain");
    private static final Identifier LUMIA_TERRAIN = Identifier.fromNamespaceAndPath("totem-lumen", "core/lumia_terrain");

    private static final Identifier NATIVE_LIGHT = Identifier.fromNamespaceAndPath("totem-lumen", "core/lumia_native_light");
    private static final ShaderDefines MASK_LIGHT = ShaderDefines.builder().define("LUMIA_NATIVE_LIGHT").build();

    private record Key(RenderPipeline source, Identifier shaders, boolean translucent) {}
    private static final Map<Key, RenderPipeline> VARIANTS = new HashMap<>();
    private static final ShaderDefines TRANSLUCENT = ShaderDefines.builder().define("LUMIA_TRANSLUCENT").build();

    private static boolean nativeTerrain(RenderPipeline source) {
        return source.getPolygonMode() != PolygonMode.WIREFRAME && TERRAIN.equals(source.getShaders().get(ShaderType.VERTEX))
                && TERRAIN.equals(source.getShaders().get(ShaderType.FRAGMENT));
    }

    public static RenderPipeline nativeGlassFallback(RenderPipeline source) {
        if (!nativeTerrain(source) || source.getShaderDefines().flags().contains("OIT_ALPHA_ONLY")) return source;
        return variant(source, NATIVE_LIGHT, false);
    }

    public static RenderPipeline terrainVariant(RenderPipeline source, boolean translucent) {
        // OIT depth/transmittance and accumulation must use identical glass coverage.
        if (!nativeTerrain(source)) return source;
        return variant(source, LUMIA_TERRAIN, translucent);
    }

    public static RenderPipeline variant(RenderPipeline source, Identifier shaders, boolean translucent) {
        return VARIANTS.computeIfAbsent(new Key(source, shaders, translucent),
                key -> new LumiaPipelines(key.source(), key.shaders(), key.translucent()));
    }

    private LumiaPipelines(RenderPipeline source, Identifier shaders, boolean translucent) {
        super(Identifier.fromNamespaceAndPath("totem-lumen", "lumia/" + shaders.getPath() + "/"
                        + source.getLocation().getNamespace() + "/" + source.getLocation().getPath()
                        + (translucent ? "/translucent" : "/solid")),
                shaders.equals(NATIVE_LIGHT) ? Map.of(ShaderType.VERTEX, LUMIA_TERRAIN, ShaderType.FRAGMENT, TERRAIN)
                        : Map.of(ShaderType.VERTEX, shaders, ShaderType.FRAGMENT, shaders),
                shaders.equals(NATIVE_LIGHT) ? source.getShaderDefines().withOverrides(MASK_LIGHT)
                        : translucent ? source.getShaderDefines().withOverrides(TRANSLUCENT) : source.getShaderDefines(),
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
