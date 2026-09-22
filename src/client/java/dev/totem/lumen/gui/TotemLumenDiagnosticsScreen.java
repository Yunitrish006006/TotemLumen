package dev.totem.lumen.gui;

import dev.totem.lumen.render.RendererBootstrap;
import dev.totem.lumen.render.RendererCompileProgressNotifier;
import dev.totem.lumen.render.RendererState;
import dev.totem.lumen.vulkan.P12FullBasePipeline;
import dev.totem.lumen.vulkan.P16MultipassReflection;
import dev.totem.lumen.vulkan.P17EnhancedBasePipeline;
import dev.totem.lumen.vulkan.P5StableLookupRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Pipeline status and render-view diagnostics kept separate from user-facing quality controls. */
public final class TotemLumenDiagnosticsScreen extends Screen {
    private static final int CONTENT_WIDTH = 310;
    private final Screen parent;
    private Button renderViewButton;

    public TotemLumenDiagnosticsScreen(Screen parent) {
        super(Component.translatable("screen.totem-lumen.diagnostics.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int contentWidth = Math.min(CONTENT_WIDTH, Math.max(220, this.width - 32));
        int left = (this.width - contentWidth) / 2;
        int halfWidth = (contentWidth - 6) / 2;

        this.renderViewButton = this.addRenderableWidget(
                Button.builder(renderViewLabel(), ignored -> {
                    P5StableLookupRenderer.cycleMode();
                    refreshRenderViewButton();
                }).bounds(left, 122, contentWidth, 20).build()
        );

        this.addRenderableWidget(
                Button.builder(Component.translatable("screen.totem-lumen.quick.gi"), ignored -> {
                    P5StableLookupRenderer.setMode(P5StableLookupRenderer.DebugMode.GI_COMPOSITE);
                    refreshRenderViewButton();
                }).bounds(left, 148, halfWidth, 20).build()
        );

        this.addRenderableWidget(
                Button.builder(Component.translatable("screen.totem-lumen.quick.fluid"), ignored -> {
                    P5StableLookupRenderer.setMode(P5StableLookupRenderer.DebugMode.FLUID_GEOMETRY);
                    refreshRenderViewButton();
                }).bounds(left + halfWidth + 6, 148, halfWidth, 20).build()
        );

        this.addRenderableWidget(
                Button.builder(Component.translatable("screen.totem-lumen.recompile"), ignored -> {
                    boolean started = P12FullBasePipeline.recompile();
                    if (started) {
                        RendererCompileProgressNotifier.reset();
                        if (this.minecraft.player != null) {
                            this.minecraft.player.sendSystemMessage(
                                    Component.translatable("message.totem-lumen.recompile.started")
                            );
                        }
                    } else if (this.minecraft.player != null) {
                        this.minecraft.player.sendSystemMessage(
                                Component.translatable("message.totem-lumen.recompile.unavailable")
                        );
                    }
                }).bounds(left, 174, contentWidth, 20).build()
        );

        this.addRenderableWidget(
                Button.builder(Component.translatable("gui.done"), ignored -> this.onClose())
                        .bounds(left, this.height - 28, contentWidth, 20)
                        .build()
        );
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float delta
    ) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        int contentWidth = Math.min(CONTENT_WIDTH, Math.max(220, this.width - 32));
        int left = (this.width - contentWidth) / 2;

        graphics.centeredText(this.font, this.title, this.width / 2, 18, 0xFFFFFFFF);

        drawStatusLine(
                graphics,
                left,
                48,
                Component.translatable("screen.totem-lumen.status.bootstrap"),
                rendererStateComponent(RendererBootstrap.state())
        );
        drawStatusLine(
                graphics,
                left,
                64,
                Component.translatable("screen.totem-lumen.status.full"),
                stageStateComponent(P12FullBasePipeline.ready(), P12FullBasePipeline.failure(), false)
        );
        drawStatusLine(
                graphics,
                left,
                80,
                Component.translatable("screen.totem-lumen.status.entities"),
                stageStateComponent(
                        P17EnhancedBasePipeline.ready(),
                        P17EnhancedBasePipeline.failure(),
                        !P12FullBasePipeline.ready()
                )
        );
        drawStatusLine(
                graphics,
                left,
                96,
                Component.translatable("screen.totem-lumen.status.reflections"),
                stageStateComponent(
                        P16MultipassReflection.ready(),
                        P16MultipassReflection.failure(),
                        !P12FullBasePipeline.ready()
                )
        );
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    private void drawStatusLine(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            Component label,
            Component value
    ) {
        graphics.text(
                this.font,
                Component.translatable("screen.totem-lumen.status.line", label, value),
                x,
                y,
                0xFFE0E0E0,
                true
        );
    }

    private void refreshRenderViewButton() {
        if (this.renderViewButton != null) {
            this.renderViewButton.setMessage(renderViewLabel());
        }
    }

    private static Component renderViewLabel() {
        return Component.translatable(
                "screen.totem-lumen.render_view",
                Component.translatable(
                        "screen.totem-lumen.mode."
                                + P5StableLookupRenderer.mode().name().toLowerCase(Locale.ROOT)
                )
        );
    }

    private static Component rendererStateComponent(RendererState state) {
        return Component.translatable(
                "screen.totem-lumen.renderer_state." + state.name().toLowerCase(Locale.ROOT)
        );
    }

    private static Component stageStateComponent(boolean ready, Throwable failure, boolean waiting) {
        if (ready) return Component.translatable("screen.totem-lumen.stage.ready");
        if (failure != null) return Component.translatable("screen.totem-lumen.stage.failed");
        if (waiting) return Component.translatable("screen.totem-lumen.stage.waiting");
        return Component.translatable("screen.totem-lumen.stage.compiling");
    }
}
