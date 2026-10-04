package dev.totem.lumen.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Entry-point guards only; runtime switching and GPU lifetime still require client validation. */
class InactiveProfileWorkContractTest {
    private static String read(String name) throws Exception {
        return Files.readString(Path.of("src/client/java/dev/totem/lumen/" + name));
    }

    @Test void environmentExtractionChecksActiveRendererBeforeWorldReads() throws Exception {
        String source = read("integration/P13EnvironmentCapture.java");
        int method = source.indexOf("private static void capture(ClientLevel level)");
        int guard = source.indexOf("if (!RendererSettings.rendererEnabled())", method);
        int worldRead = source.indexOf("level.dimension()", method);
        assertTrue(method >= 0 && guard > method && worldRead > guard);
        assertTrue(source.substring(guard, worldRead).contains("return;"));
    }

    @Test void modelAndPbrTickBelongOnlyToActiveFullRenderer() throws Exception {
        String source = read("TotemLumenClient.java");
        assertTrue(source.contains("if (RendererSettings.rendererEnabled()) {\n"
                + "                MinecraftBlockModelMeshResolver.checkModelSetReload();\n"
                + "                LabPbrTextureRegistry.tick(client);\n"
                + "            }"));
        // Cleanup is deliberately not gated: submitted work must still retire safely.
        assertTrue(source.contains("P5StableLookupRenderer.tickLifecycle(client);"));
        assertTrue(source.contains("dev.totem.lumen.vulkan.VulkanDeferredDestruction.drain();"));
    }

    @Test void inactiveDebugClicksAreConsumedInsteadOfDeferred() throws Exception {
        String source = read("TotemLumenClient.java");
        for (String key : new String[]{"cycleDebugMode", "cyclePerformanceProbe"}) {
            assertTrue(source.contains("while (" + key + ".consumeClick()) {\n"
                    + "                if (!RendererSettings.rendererEnabled()) continue;"));
        }
    }
}
