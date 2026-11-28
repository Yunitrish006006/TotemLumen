package dev.totem.lumen.integration;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import dev.totem.lumen.render.RendererSettings;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Runs the game's shaderc compiler, SPIR-V reflection and RenderPearl pipeline linker without a GPU. */
public final class LumiaPipelineVerifier {
    private LumiaPipelineVerifier() {}

    public static void main(String[] args) throws Exception {
        // Exercise the superset including the opt-in opaque material draw paths in this verifier JVM.
        System.setProperty("totem.lumen.rasterMaterialCapture", "true");
        LumiaMixinVerifier.verify();
        LumiaPreparationVerifier.verify();
        var candidates = new LinkedHashSet<>(RenderPipelines.requiredPipelines());
        candidates.addAll(RenderPipelines.optionalPipelines());
        Set<String> shaderFamilies = new LinkedHashSet<>();
        var failed = new ArrayList<String>();
        int defaults = LumiaPipelines.qualityFlags(RendererSettings.Quality.BALANCED,
                RendererSettings.LocalLightQuality.FULL, true);
        int low = LumiaPipelines.qualityFlags(RendererSettings.Quality.LOW,
                RendererSettings.LocalLightQuality.OFF, false);
        Set<String> lowDefines = Set.of("LUMIA_LOW_DAYLIGHT", "LUMIA_NO_LOCAL_LIGHTS", "LUMIA_NO_REFLECTIONS");
        int compiled = 0;
        try (var assets = new Assets(); var builder = new PipelineBuilder(frontendOnlyDevice())) {
            var rasterVariants = RasterLightingRenderer.preparationVariants();
            if (rasterVariants.size() <= 2) throw new AssertionError("Missing Raster material variants");
            for (var pipeline : rasterVariants) {
                var pending = builder.compilePipeline(pipeline, assets, Runnable::run).join();
                if (pending == CompiledRenderPipeline.Pending.NULL)
                    throw new AssertionError("Unable to link " + pipeline.getLocation());
                pending.finishCompile();
            }
            System.out.println("Linked " + rasterVariants.size() + " Raster Ray pipeline variants");
            for (var nativePipeline : candidates) {
                var variants = new LinkedHashSet<RenderPipeline>();
                var standard = LumiaPipelines.drawVariant(nativePipeline, defaults);
                var reduced = LumiaPipelines.drawVariant(nativePipeline, low);
                var fallback = LumiaPipelines.nativeGlassFallback(nativePipeline);
                if (standard != nativePipeline) {
                    if (standard.getLocation().equals(reduced.getLocation())
                            || !reduced.getShaderDefines().flags().containsAll(lowDefines)
                            || !java.util.Collections.disjoint(standard.getShaderDefines().flags(), lowDefines)
                            || standard != LumiaPipelines.drawVariant(nativePipeline, defaults)) {
                        throw new AssertionError("Incorrect Lumia quality variant for " + nativePipeline.getLocation());
                    }
                }
                if (!java.util.Collections.disjoint(fallback.getShaderDefines().flags(), lowDefines))
                    throw new AssertionError("Quality flags leaked into native fallback " + fallback.getLocation());
                variants.add(standard);
                variants.add(reduced);
                variants.add(fallback);
                variants.remove(nativePipeline);
                for (var pipeline : variants) {
                    var pending = builder.compilePipeline(pipeline, assets, Runnable::run).join();
                    if (pending == CompiledRenderPipeline.Pending.NULL) {
                        failed.add(pipeline.getLocation() + " " + pipeline.getShaderDefines());
                        continue;
                    }
                    pending.finishCompile(); // releases the intermediate SPIR-V modules
                    shaderFamilies.add(pipeline.getShaders().get(ShaderType.FRAGMENT).getPath());
                    compiled++;
                }
            }
        }
        if (!failed.isEmpty()) throw new AssertionError("Could not link Lumia pipelines " + failed);
        if (!shaderFamilies.containsAll(Set.of("core/lumia_terrain", "core/lumia_entity", "core/lumia_item",
                "core/lumia_block", "core/lumia_particle", "core/lumia_leash", "core/lumia_text", "core/lumia_clouds"))) {
            throw new AssertionError("Missing Lumia shader family in " + shaderFamilies);
        }
        System.out.println("Linked " + compiled + " Lumia pipeline variants across " + shaderFamilies);
    }

    // GPU allocation starts after the frontend has verified vertex formats, stage interfaces,
    // descriptors and fragment outputs. A null backend result lets finishCompile free all modules.
    private static GpuDeviceBackend frontendOnlyDevice() throws ReflectiveOperationException {
        var features = new DeviceFeatures(false, true, false, false, false, false, false, false);
        var device = new DeviceInfo("Lumia verification", "", "", true, "Vulkan", 1, null,
                features, Set.of(), new HintsAndWorkarounds(false, false, false, false), DeviceType.CPU);
        // GlslCompiler reads device workarounds from RenderSystem. The production initRenderer
        // also allocates textures and buffers, so install only the info provider in this test JVM.
        var field = RenderSystem.class.getDeclaredField("DEVICE");
        field.setAccessible(true);
        field.set(null, Proxy.newProxyInstance(GpuDevice.class.getClassLoader(), new Class<?>[]{GpuDevice.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getDeviceInfo")) return device;
                    throw new UnsupportedOperationException(method.toString());
                }));
        return (GpuDeviceBackend) Proxy.newProxyInstance(GpuDeviceBackend.class.getClassLoader(),
                new Class<?>[]{GpuDeviceBackend.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getDeviceInfo" -> device;
                    case "compilePipeline" -> BackendRenderPipeline.Pending.NULL;
                    default -> throw new UnsupportedOperationException(method.toString());
                });
    }

    private static final class Assets implements ShaderSource {
        private final Map<Identifier, CachedIncludeSource> includes = new HashMap<>();

        @Override
        public String getShader(Identifier id, ShaderType type) {
            return read(type.idConverter().idToFile(id));
        }

        @Override
        public CachedIncludeSource getInclude(Identifier id) {
            return includes.computeIfAbsent(id, key -> {
                String source = read(key.withPrefix("shaders/include/"));
                return source == null ? null : CachedIncludeSource.create(key, source);
            });
        }

        private static String read(Identifier file) {
            String path = "/assets/" + file.getNamespace() + "/" + file.getPath();
            try (var stream = LumiaPipelineVerifier.class.getResourceAsStream(path)) {
                return stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(path, e);
            }
        }

        @Override
        public void close() {
            includes.values().forEach(CachedIncludeSource::close);
        }
    }
}
