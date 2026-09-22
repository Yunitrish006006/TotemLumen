package dev.totem.lumen.gui;

import dev.totem.lumen.render.RendererSettings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Main Totem Lumen renderer controls exposed from Minecraft's Video Settings screen. */
public final class TotemLumenVideoSettingsScreen extends Screen {
    private static final int CONTENT_WIDTH = 330;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW_GAP = 22;

    private final Screen parent;
    private Button rendererEnabledButton;
    private Button giQualityButton;
    private Button shadowQualityButton;
    private Button rayDistanceButton;
    private Button internalResolutionButton;
    private Button reflectionsEnabledButton;
    private Button waterReflectionsButton;
    private Button reflectionBouncesButton;
    private Button reflectionDistanceButton;
    private Button temporalQualityButton;
    private Button denoiseQualityButton;

    public TotemLumenVideoSettingsScreen(Screen parent) {
        super(Component.translatable("screen.totem-lumen.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int contentWidth = Math.min(CONTENT_WIDTH, Math.max(240, this.width - 24));
        int left = (this.width - contentWidth) / 2;
        int halfWidth = (contentWidth - 6) / 2;

        this.rendererEnabledButton = addSettingButton(
                left, 44, contentWidth, rendererEnabledLabel(),
                () -> RendererSettings.toggleRendererEnabled()
        );

        int rowY = 70;
        this.giQualityButton = addSettingButton(
                left, rowY, halfWidth, giQualityLabel(),
                () -> RendererSettings.cycleGiQuality()
        );
        this.shadowQualityButton = addSettingButton(
                left + halfWidth + 6, rowY, halfWidth, shadowQualityLabel(),
                () -> RendererSettings.cycleShadowQuality()
        );
        rowY += ROW_GAP;

        this.rayDistanceButton = addSettingButton(
                left, rowY, halfWidth, rayDistanceLabel(),
                () -> RendererSettings.cycleRayDistance()
        );
        this.internalResolutionButton = addSettingButton(
                left + halfWidth + 6, rowY, halfWidth, internalResolutionLabel(),
                () -> RendererSettings.cycleInternalResolution()
        );
        rowY += ROW_GAP;

        this.reflectionsEnabledButton = addSettingButton(
                left, rowY, halfWidth, reflectionsEnabledLabel(),
                () -> RendererSettings.toggleReflectionsEnabled()
        );
        this.waterReflectionsButton = addSettingButton(
                left + halfWidth + 6, rowY, halfWidth, waterReflectionsLabel(),
                () -> RendererSettings.toggleWaterReflections()
        );
        rowY += ROW_GAP;

        this.reflectionBouncesButton = addSettingButton(
                left, rowY, halfWidth, reflectionBouncesLabel(),
                () -> RendererSettings.cycleReflectionBounces()
        );
        this.reflectionDistanceButton = addSettingButton(
                left + halfWidth + 6, rowY, halfWidth, reflectionDistanceLabel(),
                () -> RendererSettings.cycleReflectionDistance()
        );
        rowY += ROW_GAP;

        this.temporalQualityButton = addSettingButton(
                left, rowY, halfWidth, temporalQualityLabel(),
                () -> RendererSettings.cycleTemporalQuality()
        );
        this.denoiseQualityButton = addSettingButton(
                left + halfWidth + 6, rowY, halfWidth, denoiseQualityLabel(),
                () -> RendererSettings.cycleDenoiseQuality()
        );

        int diagnosticsY = Math.min(rowY + 26, this.height - 52);
        this.addRenderableWidget(
                Button.builder(
                        Component.translatable("screen.totem-lumen.diagnostics"),
                        ignored -> this.minecraft.gui.setScreen(new TotemLumenDiagnosticsScreen(this))
                ).bounds(left, diagnosticsY, contentWidth, BUTTON_HEIGHT).build()
        );

        this.addRenderableWidget(
                Button.builder(Component.translatable("gui.done"), ignored -> this.onClose())
                        .bounds(left, this.height - 28, contentWidth, BUTTON_HEIGHT)
                        .build()
        );
    }

    private Button addSettingButton(int x, int y, int width, Component label, Runnable action) {
        return this.addRenderableWidget(
                Button.builder(label, ignored -> {
                    action.run();
                    refreshSettingsButtons();
                }).bounds(x, y, width, BUTTON_HEIGHT).build()
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

        graphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        graphics.centeredText(
                this.font,
                Component.translatable("screen.totem-lumen.subtitle"),
                this.width / 2,
                26,
                0xFFAAAAAA
        );
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    private void refreshSettingsButtons() {
        if (rendererEnabledButton != null) rendererEnabledButton.setMessage(rendererEnabledLabel());
        if (giQualityButton != null) giQualityButton.setMessage(giQualityLabel());
        if (shadowQualityButton != null) shadowQualityButton.setMessage(shadowQualityLabel());
        if (rayDistanceButton != null) rayDistanceButton.setMessage(rayDistanceLabel());
        if (internalResolutionButton != null) internalResolutionButton.setMessage(internalResolutionLabel());
        if (reflectionsEnabledButton != null) reflectionsEnabledButton.setMessage(reflectionsEnabledLabel());
        if (waterReflectionsButton != null) waterReflectionsButton.setMessage(waterReflectionsLabel());
        if (reflectionBouncesButton != null) reflectionBouncesButton.setMessage(reflectionBouncesLabel());
        if (reflectionDistanceButton != null) reflectionDistanceButton.setMessage(reflectionDistanceLabel());
        if (temporalQualityButton != null) temporalQualityButton.setMessage(temporalQualityLabel());
        if (denoiseQualityButton != null) denoiseQualityButton.setMessage(denoiseQualityLabel());
    }

    static Component rendererEnabledLabel() {
        return Component.translatable(
                "screen.totem-lumen.renderer_enabled",
                booleanComponent(RendererSettings.rendererEnabled())
        );
    }

    static Component giQualityLabel() {
        return Component.translatable(
                "screen.totem-lumen.gi_quality",
                qualityName(RendererSettings.giQuality().name())
        );
    }

    static Component shadowQualityLabel() {
        return Component.translatable(
                "screen.totem-lumen.shadow_quality",
                qualityComponent(RendererSettings.shadowQuality())
        );
    }

    static Component rayDistanceLabel() {
        return Component.translatable("screen.totem-lumen.ray_distance", RendererSettings.rayDistance());
    }

    static Component internalResolutionLabel() {
        RendererSettings.InternalResolution value = RendererSettings.internalResolution();
        return Component.translatable(
                "screen.totem-lumen.internal_resolution",
                qualityName(value.name()),
                value.percent()
        );
    }

    static Component reflectionsEnabledLabel() {
        return Component.translatable(
                "screen.totem-lumen.reflections_enabled",
                booleanComponent(RendererSettings.reflectionsEnabled())
        );
    }

    static Component waterReflectionsLabel() {
        return Component.translatable(
                "screen.totem-lumen.water_reflections",
                booleanComponent(RendererSettings.waterReflections())
        );
    }

    static Component reflectionBouncesLabel() {
        return Component.translatable(
                "screen.totem-lumen.reflection_bounces",
                Component.translatable(
                        "screen.totem-lumen.value.bounces",
                        RendererSettings.reflectionBounces()
                )
        );
    }

    static Component reflectionDistanceLabel() {
        return Component.translatable(
                "screen.totem-lumen.reflection_distance",
                RendererSettings.reflectionDistance()
        );
    }

    static Component temporalQualityLabel() {
        return Component.translatable(
                "screen.totem-lumen.temporal_quality",
                qualityName(RendererSettings.temporalQuality().name())
        );
    }

    static Component denoiseQualityLabel() {
        return Component.translatable(
                "screen.totem-lumen.denoise_quality",
                qualityName(RendererSettings.denoiseQuality().name())
        );
    }

    private static Component qualityComponent(RendererSettings.Quality quality) {
        return qualityName(quality.name());
    }

    private static Component qualityName(String name) {
        return Component.translatable(
                "screen.totem-lumen.quality." + name.toLowerCase(Locale.ROOT)
        );
    }

    private static Component booleanComponent(boolean enabled) {
        return Component.translatable(
                enabled ? "screen.totem-lumen.value.on" : "screen.totem-lumen.value.off"
        );
    }


}
