package dev.totem.lumen.integration;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import dev.totem.lumen.mixin.LumiaCloudDrawMixin;
import dev.totem.lumen.mixin.LumiaWeatherDrawMixin;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.InputStreamReader;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Checks the native draw boundaries and call sites used by the Lumia client mixins. */
final class LumiaMixinVerifier {
    private LumiaMixinVerifier() {}

    static void verify() throws Exception {
        try (var config = new InputStreamReader(LumiaMixinVerifier.class.getResourceAsStream(
                "/totem-lumen.client.mixins.json"), StandardCharsets.UTF_8)) {
            var mixins = JsonParser.parseReader(config).getAsJsonObject().getAsJsonArray("client");
            for (String name : new String[]{"LumiaTerrainMixin", "LumiaPipelineCacheLifecycleMixin", "LumiaPipelineCacheAccessor", "LumiaGlassBackgroundMixin", "LumiaWorldDrawMixin",
                    "LumiaActorDrawMixin", "LumiaParticleDrawMixin", "LumiaCloudDrawMixin", "LumiaWeatherDrawMixin", "LumiaSignGlowMixin"}) {
                if (!mixins.contains(new com.google.gson.JsonPrimitive(name))) {
                    throw new AssertionError("Missing client mixin " + name);
                }
            }
        }
        String cache = "Lcom/mojang/blaze3d/pipeline/PipelineCache;";
        var apply = method(ShaderManager.class, "apply");
        int replace = call(apply, "com/mojang/blaze3d/systems/RenderSystem", "setCurrentPipelineCache", "(" + cache + ")" + cache);
        boolean closesOldCache = false;
        for (var instruction : apply.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals("com/mojang/blaze3d/pipeline/PipelineCache")
                    && call.name.equals("close") && apply.instructions.indexOf(call) > replace) closesOldCache = true;
        }
        if (!closesOldCache) throw new AssertionError("Shader reload no longer retires the old pipeline cache after replacement");
        call(method(RenderSystem.class, "shutdownRenderer"), "com/mojang/blaze3d/pipeline/PipelineCache", "close", "()V");
        if (PipelineCache.class.getDeclaredField("device").getType() != GpuDevice.class
                || PipelineCache.class.getDeclaredField("shaderSource").getType() != ShaderSource.class
                || PipelineCache.class.getDeclaredField("cache").getType() != java.util.Map.class)
            throw new AssertionError("Native pipeline cache accessor contract changed");
        GameRenderer.class.getDeclaredMethod("renderLevel");
        var actor = method(PreparedRenderType.class, "draw");
        var particle = method(QuadParticleFeatureRenderer.class, "drawLayers");
        String pipeline = "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;";
        String compiled = "Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;";
        for (var method : new MethodNode[]{actor, particle}) {
            call(method, "com/mojang/blaze3d/systems/RenderSystem", "getCompiledPipeline", "(" + pipeline + ")" + compiled);
            call(method, "com/mojang/renderpearl/api/commands/RenderPass", "setPipeline", "(" + compiled + ")V");
        }
        String pass = "Lcom/mojang/renderpearl/api/commands/RenderPass;";
        String weather = "Lnet/minecraft/client/renderer/state/level/WeatherRenderState;";
        var clouds = method(CloudRenderer.class, "render", "(" + pass + pipeline + ")V");
        var precipitation = method(WeatherEffectRenderer.class, "render", "(" + weather + pass + pipeline + ")V");
        verifyDrawSelector(LumiaCloudDrawMixin.class, clouds);
        verifyDrawSelector(LumiaWeatherDrawMixin.class, precipitation);
        for (var method : new MethodNode[]{clouds, precipitation}) {
            call(method, "com/mojang/renderpearl/api/commands/RenderPass", "setPipeline", "(" + compiled + ")V");
        }
        boolean glow = false;
        for (var instruction : method(AbstractSignRenderer.class, "submitSignText").instructions) {
            if (instruction instanceof LdcInsnNode constant && Integer.valueOf(15728880).equals(constant.cst)) glow = true;
        }
        if (!glow) throw new AssertionError("Missing glowing sign light constant");

        verifyGlassCaptureOrder(pass);

        String terrain = "net/minecraft/client/renderer/chunk/ChunkSectionsToRender";
        call(method(ChunkSectionsToRender.class, "renderLayers"), terrain, "render",
                "(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;Lcom/mojang/renderpearl/api/commands/RenderPass;"
                + "Lcom/mojang/renderpearl/api/buffers/GpuBuffer;Lcom/mojang/renderpearl/api/pipeline/IndexType;"
                + pipeline + pipeline + ")V");
    }

    private static void verifyGlassCaptureOrder(String pass) throws Exception {
        var main = method(LevelRenderer.class, "lambda$addMainPass$0");
        String level = "net/minecraft/client/renderer/LevelRenderer";
        String chunks = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;";
        String features = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;";
        int solid = call(main, level, "executeSolid", "(" + chunks + features + pass + ")V");
        int classic = call(main, level, "executeClassicTransparency", "(" + chunks + features + pass + ")V");
        int close = call(main, "com/mojang/renderpearl/api/commands/RenderPass", "close", "()V");
        int oit = call(main, level, "executeOit", "(" + chunks + features + ")V");
        int outline = call(main, level, "executeOutline", "(" + features + ")V");
        if (!(solid < classic && classic < close && close < oit && oit < outline)) {
            throw new AssertionError("Opaque/transparent world pass boundaries changed");
        }
    }

    private static void verifyDrawSelector(Class<?> mixin, MethodNode target) {
        var loader = mixin.getClassLoader();
        var callback = MethodType.fromMethodDescriptorString(target.desc, loader).appendParameterTypes(CallbackInfo.class);
        String[] selector = {target.name + target.desc};
        boolean select = false;
        boolean bind = false;
        for (var handler : mixin.getDeclaredMethods()) {
            var modify = handler.getAnnotation(ModifyVariable.class);
            if (modify != null) {
                select = Arrays.equals(selector, modify.method()) && modify.argsOnly()
                        && modify.at().length == 1 && modify.at()[0].value().equals("HEAD");
            }
            var inject = handler.getAnnotation(Inject.class);
            if (inject != null) {
                bind = Arrays.equals(selector, inject.method()) && Arrays.equals(callback.parameterArray(), handler.getParameterTypes());
            }
        }
        if (!select || !bind) throw new AssertionError("Invalid draw selector or callback for " + mixin.getSimpleName());
    }

    private static MethodNode method(Class<?> type, String name) throws Exception {
        return method(type, name, null);
    }

    private static MethodNode method(Class<?> type, String name, String descriptor) throws Exception {
        var node = new ClassNode();
        try (var input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        var methods = node.methods.stream().filter(m -> m.name.equals(name) && (descriptor == null || descriptor.equals(m.desc))).toList();
        if (methods.size() != 1) throw new AssertionError("Ambiguous or absent " + type.getName() + "." + name);
        return methods.getFirst();
    }

    private static int call(MethodNode method, String owner, String name, String descriptor) {
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name) && call.desc.equals(descriptor)) return method.instructions.indexOf(call);
        }
        throw new AssertionError(method.name + method.desc + " does not call " + owner + "." + name + descriptor);
    }
}
