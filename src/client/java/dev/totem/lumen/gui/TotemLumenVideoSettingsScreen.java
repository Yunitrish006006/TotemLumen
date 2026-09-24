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
    private RendererSettingSlider renderProfileSlider;
    private RendererSettingSlider giQualitySlider;
    private RendererSettingSlider shadowQualitySlider;
    private RendererSettingSlider rayDistanceSlider;
    private RendererSettingSlider internalResolutionSlider;
    private Button reflectionsEnabledButton;
    private Button waterReflectionsButton;
    private Button entityRayTracingButton;
    private Button localLightQualityButton;
    private RendererSettingSlider reflectionBouncesSlider;
    private RendererSettingSlider reflectionDistanceSlider;
    private RendererSettingSlider temporalQualitySlider;
    private RendererSettingSlider denoiseQualitySlider;

    public TotemLumenVideoSettingsScreen(Screen parent) {
        super(Component.translatable("screen.totem-lumen.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int contentWidth = Math.min(CONTENT_WIDTH, Math.max(240, this.width - 24));
        int left = (this.width - contentWidth) / 2;
        int halfWidth = (contentWidth - 6) / 2;

        this.renderProfileSlider = addSettingSlider(
                left, 44, contentWidth, RendererSettings.RenderProfile.values().length - 1,
                () -> RendererSettings.renderProfile().ordinal(),
                RendererSettings::setRenderProfile,
                index -> renderProfileLabel(RendererSettings.RenderProfile.values()[index])
        );

        int rowY = 70;
        this.giQualitySlider = addSettingSlider(
                left, rowY, halfWidth, RendererSettings.GiQuality.values().length - 1,
                () -> RendererSettings.giQuality().ordinal(),
                RendererSettings::setGiQuality,
                index -> giQualityLabel(RendererSettings.GiQuality.values()[index])
        );
        this.shadowQualitySlider = addSettingSlider(
                left + halfWidth + 6, rowY, halfWidth, RendererSettings.Quality.values().length - 1,
                () -> RendererSettings.shadowQuality().ordinal(),
                RendererSettings::setShadowQuality,
                index -> shadowQualityLabel(RendererSettings.Quality.values()[index])
        );
        rowY += ROW_GAP;

        this.rayDistanceSlider = addSettingSlider(
                left, rowY, halfWidth, 3,
                () -> rayDistanceIndex(RendererSettings.rayDistance()),
                RendererSettings::setRayDistanceIndex,
                index -> rayDistanceLabel(new int[]{32, 64, 96, 128}[index])
        );
        this.internalResolutionSlider = addSettingSlider(
                left + halfWidth + 6, rowY, halfWidth, RendererSettings.InternalResolution.values().length - 1,
                () -> RendererSettings.internalResolution().ordinal(),
                RendererSettings::setInternalResolution,
                index -> internalResolutionLabel(RendererSettings.InternalResolution.values()[index])
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

        this.entityRayTracingButton = addSettingButton(
                left + halfWidth + 6, rowY, halfWidth, entityRayTracingLabel(),
                RendererSettings::toggleEntityRayTracing
        );
        this.localLightQualityButton = addSettingButton(
                left, rowY, halfWidth, localLightQualityLabel(),
                RendererSettings::cycleLocalLightQuality
        );
        rowY += ROW_GAP;

        this.reflectionBouncesSlider = addSettingSlider(
                left, rowY, halfWidth, 1,
                () -> RendererSettings.reflectionBounces() - 1,
                index -> RendererSettings.setReflectionBounces(index + 1),
                index -> reflectionBouncesLabel(index + 1)
        );
        this.reflectionDistanceSlider = addSettingSlider(
                left + halfWidth + 6, rowY, halfWidth, 2,
                () -> reflectionDistanceIndex(RendererSettings.reflectionDistance()),
                RendererSettings::setReflectionDistanceIndex,
                index -> reflectionDistanceLabel(new int[]{16, 32, 64}[index])
        );
        rowY += ROW_GAP;

        this.temporalQualitySlider = addSettingSlider(
                left, rowY, halfWidth, RendererSettings.TemporalQuality.values().length - 1,
                () -> RendererSettings.temporalQuality().ordinal(),
                RendererSettings::setTemporalQuality,
                index -> temporalQualityLabel(RendererSettings.TemporalQuality.values()[index])
        );
        this.denoiseQualitySlider = addSettingSlider(
                left + halfWidth + 6, rowY, halfWidth, RendererSettings.DenoiseQuality.values().length - 1,
                () -> RendererSettings.denoiseQuality().ordinal(),
                RendererSettings::setDenoiseQuality,
                index -> denoiseQualityLabel(RendererSettings.DenoiseQuality.values()[index])
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

    private RendererSettingSlider addSettingSlider(
            int x,
            int y,
            int width,
            int maximumIndex,
            java.util.function.IntSupplier currentIndex,
            java.util.function.IntConsumer applyIndex,
            java.util.function.Function<Integer, Component> label
    ) {
        return this.addRenderableWidget(new RendererSettingSlider(
                x, y, width, BUTTON_HEIGHT, maximumIndex, currentIndex, applyIndex, label
        ));
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
        if (renderProfileSlider != null) {
            renderProfileSlider.setMessage(renderProfileLabel());
            renderProfileSlider.active = true;
        }
        if (reflectionsEnabledButton != null) reflectionsEnabledButton.setMessage(reflectionsEnabledLabel());
        if (waterReflectionsButton != null) waterReflectionsButton.setMessage(waterReflectionsLabel());
        if (entityRayTracingButton != null) entityRayTracingButton.setMessage(entityRayTracingLabel());
        if (localLightQualityButton != null) localLightQualityButton.setMessage(localLightQualityLabel());
    }

    static Component renderProfileLabel() {
        return renderProfileLabel(RendererSettings.renderProfile());
    }

    static Component renderProfileLabel(RendererSettings.RenderProfile profile) {
        String key = switch (profile) {
            case MINECRAFT_PURE -> "screen.totem-lumen.render_profile.minecraft_pure";
            case MINECRAFT_RGB -> "screen.totem-lumen.render_profile.minecraft_rgb";
            case TOTEM_LUMEN -> "screen.totem-lumen.render_profile.totem";
        };
        return Component.translatable(
                "screen.totem-lumen.render_profile",
                Component.translatable(key)
        );
    }

    static Component rendererEnabledLabel() {
        return renderProfileLabel();
    }

    static Component giQualityLabel() {
        return giQualityLabel(RendererSettings.giQuality());
    }

    static Component giQualityLabel(RendererSettings.GiQuality quality) {
        return Component.translatable(
                "screen.totem-lumen.gi_quality",
                qualityName(quality.name())
        );
    }

    static Component shadowQualityLabel() {
        return shadowQualityLabel(RendererSettings.shadowQuality());
    }

    static Component shadowQualityLabel(RendererSettings.Quality quality) {
        return Component.translatable(
                "screen.totem-lumen.shadow_quality",
                qualityComponent(quality)
        );
    }

    static Component rayDistanceLabel(int distance) {
        return Component.translatable("screen.totem-lumen.ray_distance", distance);
    }

    static Component rayDistanceLabel() {
        return Component.translatable("screen.totem-lumen.ray_distance", RendererSettings.rayDistance());
    }

    static Component internalResolutionLabel() {
        return internalResolutionLabel(RendererSettings.internalResolution());
    }

    static Component internalResolutionLabel(RendererSettings.InternalResolution value) {
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

    static Component entityRayTracingLabel() {
        return Component.translatable(
                "screen.totem-lumen.entity_ray_tracing",
                booleanComponent(RendererSettings.entityRayTracingEnabled())
        );
    }

    static Component localLightQualityLabel() {
        return Component.translatable(
                "screen.totem-lumen.local_light_quality",
                Component.translatable(
                        "screen.totem-lumen.quality." + RendererSettings.localLightQuality().name().toLowerCase(Locale.ROOT)
                )
        );
    }

    static Component reflectionBouncesLabel() {
        return reflectionBouncesLabel(RendererSettings.reflectionBounces());
    }

    static Component reflectionBouncesLabel(int bounces) {
        return Component.translatable(
                "screen.totem-lumen.reflection_bounces",
                Component.translatable(
                        "screen.totem-lumen.value.bounces",
                        bounces
                )
        );
    }

    static Component reflectionDistanceLabel(int distance) {
        return Component.translatable("screen.totem-lumen.reflection_distance", distance);
    }

    static Component reflectionDistanceLabel() {
        return Component.translatable(
                "screen.totem-lumen.reflection_distance",
                RendererSettings.reflectionDistance()
        );
    }

    static Component temporalQualityLabel() {
        return temporalQualityLabel(RendererSettings.temporalQuality());
    }

    static Component temporalQualityLabel(RendererSettings.TemporalQuality quality) {
        return Component.translatable(
                "screen.totem-lumen.temporal_quality",
                qualityName(quality.name())
        );
    }

    static Component denoiseQualityLabel() {
        return denoiseQualityLabel(RendererSettings.denoiseQuality());
    }

    static Component denoiseQualityLabel(RendererSettings.DenoiseQuality quality) {
        return Component.translatable(
                "screen.totem-lumen.denoise_quality",
                qualityName(quality.name())
        );
    }

    private static int rayDistanceIndex(int value) {
        return value <= 32 ? 0 : value <= 64 ? 1 : value <= 96 ? 2 : 3;
    }

    private static int reflectionDistanceIndex(int value) {
        return value <= 16 ? 0 : value <= 32 ? 1 : 2;
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
