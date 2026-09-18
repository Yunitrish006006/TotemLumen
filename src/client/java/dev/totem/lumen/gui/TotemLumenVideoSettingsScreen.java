package dev.totem.lumen.gui;

import dev.totem.lumen.render.RendererBootstrap;
import dev.totem.lumen.render.RendererState;
import dev.totem.lumen.render.RendererSettings;
import dev.totem.lumen.vulkan.P12FullBasePipeline;
import dev.totem.lumen.vulkan.P16MultipassReflection;
import dev.totem.lumen.vulkan.P17EnhancedBasePipeline;
import dev.totem.lumen.vulkan.P5StableLookupRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Totem Lumen renderer controls exposed from Minecraft's Video Settings screen. */
public final class TotemLumenVideoSettingsScreen extends Screen {
    private static final int CONTENT_WIDTH = 310;
    private static final int BUTTON_HEIGHT = 20;

    private final Screen parent;
    private Button reflectionBouncesButton;
    private Button reflectionDistanceButton;
    private Button renderViewButton;

    public TotemLumenVideoSettingsScreen(Screen parent) {
        super(Component.translatable("screen.totem-lumen.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int contentWidth = Math.min(CONTENT_WIDTH, Math.max(220, this.width - 32));
        int left = (this.width - contentWidth) / 2;
        int halfWidth = (contentWidth - 6) / 2;
        int doneY = Math.min(this.height - 28, 246);
        int quickY = doneY - 26;
        int renderViewY = quickY - 26;
        int reflectionDistanceY = renderViewY - 26;
        int reflectionBouncesY = reflectionDistanceY - 26;

        this.reflectionBouncesButton = this.addRenderableWidget(
                Button.builder(reflectionBouncesLabel(), ignored -> {
                    RendererSettings.cycleReflectionBounces();
                    refreshSettingsButtons();
                }).bounds(left, reflectionBouncesY, contentWidth, BUTTON_HEIGHT).build()
        );

        this.reflectionDistanceButton = this.addRenderableWidget(
                Button.builder(reflectionDistanceLabel(), ignored -> {
                    RendererSettings.cycleReflectionDistance();
                    refreshSettingsButtons();
                }).bounds(left, reflectionDistanceY, contentWidth, BUTTON_HEIGHT).build()
        );

        this.renderViewButton = this.addRenderableWidget(
                Button.builder(renderViewLabel(), ignored -> {
                    P5StableLookupRenderer.cycleMode();
                    refreshSettingsButtons();
                }).bounds(left, renderViewY, contentWidth, BUTTON_HEIGHT).build()
        );

        this.addRenderableWidget(
                Button.builder(Component.translatable("screen.totem-lumen.quick.gi"), ignored -> {
                    P5StableLookupRenderer.setMode(P5StableLookupRenderer.DebugMode.GI_COMPOSITE);
                    refreshSettingsButtons();
                }).bounds(left, quickY, halfWidth, BUTTON_HEIGHT).build()
        );

        this.addRenderableWidget(
                Button.builder(Component.translatable("screen.totem-lumen.quick.fluid"), ignored -> {
                    P5StableLookupRenderer.setMode(P5StableLookupRenderer.DebugMode.FLUID_GEOMETRY);
                    refreshSettingsButtons();
                }).bounds(left + halfWidth + 6, quickY, halfWidth, BUTTON_HEIGHT).build()
        );

        this.addRenderableWidget(
                Button.builder(Component.translatable("gui.done"), ignored -> this.onClose())
                        .bounds(left, doneY, contentWidth, BUTTON_HEIGHT)
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
        graphics.centeredText(
                this.font,
                Component.translatable("screen.totem-lumen.subtitle"),
                this.width / 2,
                36,
                0xFFAAAAAA
        );

        drawStatusLine(
                graphics,
                left,
                58,
                Component.translatable("screen.totem-lumen.status.bootstrap"),
                rendererStateComponent(RendererBootstrap.state())
        );
        drawStatusLine(
                graphics,
                left,
                72,
                Component.translatable("screen.totem-lumen.status.full"),
                stageStateComponent(P12FullBasePipeline.ready(), P12FullBasePipeline.failure(), false)
        );
        drawStatusLine(
                graphics,
                left,
                86,
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
                100,
                Component.translatable("screen.totem-lumen.status.reflections"),
                stageStateComponent(
                        P16MultipassReflection.ready(),
                        P16MultipassReflection.failure(),
                        !P12FullBasePipeline.ready()
                )
        );

        if (this.height >= 286) {
            graphics.text(
                    this.font,
                    Component.translatable("screen.totem-lumen.footer_hint"),
                    left,
                    260,
                    0xFF888888,
                    false
            );
        }
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

    private void refreshSettingsButtons() {
        if (this.reflectionBouncesButton != null) {
            this.reflectionBouncesButton.setMessage(reflectionBouncesLabel());
        }
        if (this.reflectionDistanceButton != null) {
            this.reflectionDistanceButton.setMessage(reflectionDistanceLabel());
        }
        if (this.renderViewButton != null) {
            this.renderViewButton.setMessage(renderViewLabel());
        }
    }

    private static Component reflectionBouncesLabel() {
        int bounces = RendererSettings.reflectionBounces();
        Component value = bounces == 0
                ? Component.translatable("screen.totem-lumen.value.off")
                : Component.translatable("screen.totem-lumen.value.bounces", bounces);
        return Component.translatable("screen.totem-lumen.reflection_bounces", value);
    }

    private static Component reflectionDistanceLabel() {
        return Component.translatable(
                "screen.totem-lumen.reflection_distance",
                RendererSettings.reflectionDistance()
        );
    }

    private static Component renderViewLabel() {
        return Component.translatable(
                "screen.totem-lumen.render_view",
                modeComponent(P5StableLookupRenderer.mode())
        );
    }

    private static Component modeComponent(P5StableLookupRenderer.DebugMode mode) {
        return Component.translatable(
                "screen.totem-lumen.mode." + mode.name().toLowerCase(Locale.ROOT)
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
