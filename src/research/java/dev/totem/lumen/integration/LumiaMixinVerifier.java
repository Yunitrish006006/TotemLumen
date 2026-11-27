package dev.totem.lumen.integration;

import com.google.gson.JsonParser;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.CloudRenderer;
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

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Checks the native draw boundaries and call sites used by the Lumia client mixins. */
final class LumiaMixinVerifier {
    private LumiaMixinVerifier() {}

    static void verify() throws Exception {
        try (var config = new InputStreamReader(LumiaMixinVerifier.class.getResourceAsStream(
                "/totem-lumen.client.mixins.json"), StandardCharsets.UTF_8)) {
            var mixins = JsonParser.parseReader(config).getAsJsonObject().getAsJsonArray("client");
            for (String name : new String[]{"LumiaTerrainMixin", "LumiaWorldDrawMixin",
                    "LumiaActorDrawMixin", "LumiaParticleDrawMixin", "LumiaAtmosphereDrawMixin", "LumiaSignGlowMixin"}) {
                if (!mixins.contains(new com.google.gson.JsonPrimitive(name))) {
                    throw new AssertionError("Missing client mixin " + name);
                }
            }
        }
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
        for (var method : new MethodNode[]{clouds, precipitation}) {
            call(method, "com/mojang/renderpearl/api/commands/RenderPass", "setPipeline", "(" + compiled + ")V");
        }
        boolean glow = false;
        for (var instruction : method(AbstractSignRenderer.class, "submitSignText").instructions) {
            if (instruction instanceof LdcInsnNode constant && Integer.valueOf(15728880).equals(constant.cst)) glow = true;
        }
        if (!glow) throw new AssertionError("Missing glowing sign light constant");

        String terrain = "net/minecraft/client/renderer/chunk/ChunkSectionsToRender";
        call(method(ChunkSectionsToRender.class, "renderLayers"), terrain, "render",
                "(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;Lcom/mojang/renderpearl/api/commands/RenderPass;"
                + "Lcom/mojang/renderpearl/api/buffers/GpuBuffer;Lcom/mojang/renderpearl/api/pipeline/IndexType;"
                + pipeline + pipeline + ")V");
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

    private static void call(MethodNode method, String owner, String name, String descriptor) {
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name) && call.desc.equals(descriptor)) return;
        }
        throw new AssertionError(method.name + method.desc + " does not call " + owner + "." + name + descriptor);
    }
}
