package dev.totem.lumen.render;

import dev.totem.lumen.integration.RasterMaterialCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

public final class RasterPreparationHud {
    public enum Stage { WORLD, BACKEND, LIGHTING, COMPOSITE, MATERIAL, SECTIONS, FRAME, READY }
    private static Stage stage = Stage.WORLD;
    private static long started, finished, previous;
    private static boolean failed;
    private static int count, total;
    private static double fill;

    private RasterPreparationHud() {}

    public static void reset() {
        stage = Stage.WORLD;
        started = finished = previous = 0;
        count = total = 0;
        fill = 0;
        failed = false;
    }

    public static void stage(Stage next) {
        if (finished == 0 && !failed) stage = next;
    }

    public static void sections(int uploaded, int slots) {
        stage(Stage.SECTIONS);
        count = uploaded;
        total = slots;
    }

    public static void presented() {
        if (finished == 0 && !failed) {
            stage = Stage.READY;
            finished = System.nanoTime();
        }
    }

    public static void fail(Stage at) {
        stage = at;
        finished = 0;
        failed = true;
    }

    public static void draw(GuiGraphicsExtractor g) {
        var client = Minecraft.getInstance();
        if (!RendererSettings.rasterLightingEnabled() || client.level == null || client.gui.hud.isHidden()
                || RasterMaterialCapture.rawPreviewRequested()) return;
        long now = System.nanoTime();
        if (started == 0) started = now;
        double opacity = finished == 0 ? 1 : Math.min(1, 1 - (now - finished - 650_000_000L) / 450_000_000.0);
        if (opacity <= 0) return;
        int alpha = (int) (255 * opacity);
        long tenths = Math.max(0, (now - started) / 100_000_000L);
        var title = Component.translatable("hud.totem-lumen.raster." + (failed ? "failed" : "title"));
        String key = "hud.totem-lumen.raster." + stage.name().toLowerCase(java.util.Locale.ROOT);
        var detail = Component.translatable(key, count, total);
        String time = tenths / 10 + "." + tenths % 10 + "s";
        int width = Math.max(148, client.font.width(detail) + 4);
        var font = client.font;
        g.fill(4, 4, 12 + width, 34, (int)(156 * opacity) << 24);
        g.text(font, title, 8, 7, alpha << 24 | (failed ? 0xffaaaa : 0xffffff), false);
        if (!failed && finished == 0) g.text(font, time, 8 + width - font.width(time), 7, alpha << 24 | 0xcccccc, false);
        g.text(font, detail, 8, 18, alpha << 24 | 0xe0e0e0, false);
        g.fill(8, 29, 8 + width, 31, alpha << 24 | 0x474747);
        if (!failed) {
            int left = 0, right;
            if (stage == Stage.SECTIONS || stage == Stage.READY || stage == Stage.FRAME) {
                double target = stage == Stage.SECTIONS ? count / (double) Math.max(1, total) : 1;
                fill = Math.min(target, target + (fill - target) * Math.exp(-(now - previous) / 90_000_000.0));
                right = (int) Math.round(width * fill);
            } else {
                int span = width - 24;
                int offset = (int)((now - started) / 7_000_000L % (2 * span));
                left = span - Math.abs(span - offset);
                right = left + 24;
            }
            g.fill(8 + left, 29, 8 + right, 31, alpha << 24 | 0xdadada);
        }
        previous = now;
    }
}
