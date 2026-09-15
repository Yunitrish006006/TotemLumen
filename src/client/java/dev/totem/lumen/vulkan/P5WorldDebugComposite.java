package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Compatibility bootstrap retained for the persistent renderer transition.
 *
 * <p>Older development builds used this class as a one-shot P5C validation pass. That pass
 * duplicated scene packing, shader compilation, Vulkan buffer allocation and image composition
 * immediately after joining a world. The persistent {@link P5StableLookupRenderer} supersedes
 * that validation path, so normal runtime now marks this gate ready without doing GPU work.</p>
 */
public final class P5WorldDebugComposite {
    private static boolean attempted;
    private static volatile boolean ready;

    private P5WorldDebugComposite() {
    }

    public static void runOnceOnRenderThread() {
        if (attempted) {
            return;
        }
        attempted = true;
        ready = true;
        TotemLumenClient.LOGGER.info(
                "Legacy P5 world-debug bootstrap skipped; persistent renderer owns the runtime Vulkan path"
        );
    }

    /**
     * The compatibility gate intentionally has no fallback texture. Until the persistent renderer
     * completes its first frame, vanilla Minecraft remains visible instead of running legacy P5
     * validation/composite work during world entry.
     */
    public static void drawHud(GuiGraphicsExtractor graphics) {
        // Intentionally empty.
    }

    public static boolean ready() {
        return ready;
    }
}
