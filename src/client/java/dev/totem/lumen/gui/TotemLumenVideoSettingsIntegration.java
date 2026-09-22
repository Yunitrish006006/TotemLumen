package dev.totem.lumen.gui;

import dev.totem.lumen.render.RendererSettings;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Integrates Totem Lumen directly into Minecraft's Video Settings screen.
 *
 * <p>When Totem Lumen owns world presentation, vanilla controls that only affect Minecraft's
 * replaced world renderer are locked in place. Display/interface controls and settings that still
 * affect scene extraction remain available.</p>
 */
public final class TotemLumenVideoSettingsIntegration {
    private static boolean initialized;

    private TotemLumenVideoSettingsIntegration() {
    }

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof VideoSettingsScreen videoSettings)) return;

            OptionsList optionsList = Screens.getWidgets(screen).stream()
                    .filter(OptionsList.class::isInstance)
                    .map(OptionsList.class::cast)
                    .findFirst()
                    .orElse(null);
            if (optionsList == null) return;

            new IntegratedControls(client, videoSettings, optionsList).install();
        });
    }

    private static final class IntegratedControls {
        private final Minecraft client;
        private final VideoSettingsScreen screen;
        private final OptionsList list;
        private final List<AbstractWidget> vanillaRendererWidgets = new ArrayList<>();

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

        private IntegratedControls(
                Minecraft client,
                VideoSettingsScreen screen,
                OptionsList list
        ) {
            this.client = client;
            this.screen = screen;
            this.list = list;
        }

        private void install() {
            collectVanillaRendererWidgets(client.options);

            list.addHeader(Component.translatable("screen.totem-lumen.video_section"));

            rendererEnabledButton = button(
                    TotemLumenVideoSettingsScreen.rendererEnabledLabel(),
                    RendererSettings::toggleRendererEnabled
            );
            Button diagnosticsButton = Button.builder(
                    Component.translatable("screen.totem-lumen.diagnostics"),
                    ignored -> client.gui.setScreen(new TotemLumenDiagnosticsScreen(screen))
            ).bounds(0, 0, 150, 20).build();
            list.addSmall(rendererEnabledButton, diagnosticsButton);

            giQualityButton = button(
                    TotemLumenVideoSettingsScreen.giQualityLabel(),
                    RendererSettings::cycleGiQuality
            );
            shadowQualityButton = button(
                    TotemLumenVideoSettingsScreen.shadowQualityLabel(),
                    RendererSettings::cycleShadowQuality
            );
            list.addSmall(giQualityButton, shadowQualityButton);

            rayDistanceButton = button(
                    TotemLumenVideoSettingsScreen.rayDistanceLabel(),
                    RendererSettings::cycleRayDistance
            );
            internalResolutionButton = button(
                    TotemLumenVideoSettingsScreen.internalResolutionLabel(),
                    RendererSettings::cycleInternalResolution
            );
            list.addSmall(rayDistanceButton, internalResolutionButton);

            reflectionsEnabledButton = button(
                    TotemLumenVideoSettingsScreen.reflectionsEnabledLabel(),
                    RendererSettings::toggleReflectionsEnabled
            );
            waterReflectionsButton = button(
                    TotemLumenVideoSettingsScreen.waterReflectionsLabel(),
                    RendererSettings::toggleWaterReflections
            );
            list.addSmall(reflectionsEnabledButton, waterReflectionsButton);

            reflectionBouncesButton = button(
                    TotemLumenVideoSettingsScreen.reflectionBouncesLabel(),
                    RendererSettings::cycleReflectionBounces
            );
            reflectionDistanceButton = button(
                    TotemLumenVideoSettingsScreen.reflectionDistanceLabel(),
                    RendererSettings::cycleReflectionDistance
            );
            list.addSmall(reflectionBouncesButton, reflectionDistanceButton);

            temporalQualityButton = button(
                    TotemLumenVideoSettingsScreen.temporalQualityLabel(),
                    RendererSettings::cycleTemporalQuality
            );
            denoiseQualityButton = button(
                    TotemLumenVideoSettingsScreen.denoiseQualityLabel(),
                    RendererSettings::cycleDenoiseQuality
            );
            list.addSmall(temporalQualityButton, denoiseQualityButton);

            refresh();
        }

        private Button button(Component label, Runnable action) {
            return Button.builder(label, ignored -> {
                action.run();
                refresh();
            }).bounds(0, 0, 150, 20).build();
        }

        private void collectVanillaRendererWidgets(Options options) {
            addVanillaWidget(options.graphicsPreset());
            addVanillaWidget(options.ambientOcclusion());
            addVanillaWidget(options.entityShadows());
            addVanillaWidget(options.cloudStatus());
            addVanillaWidget(options.cloudRange());
            addVanillaWidget(options.weatherRadius());
            addVanillaWidget(options.cutoutLeaves());
            addVanillaWidget(options.improvedTransparency());
            addVanillaWidget(options.chunkSectionFadeInTime());
            addVanillaWidget(options.textureFiltering());
        }

        private void addVanillaWidget(net.minecraft.client.OptionInstance<?> option) {
            AbstractWidget widget = list.findOption(option);
            if (widget != null) {
                vanillaRendererWidgets.add(widget);
            }
        }

        private void refresh() {
            boolean rendererEnabled = RendererSettings.rendererEnabled();
            boolean reflectionsEnabled = rendererEnabled && RendererSettings.reflectionsEnabled();

            rendererEnabledButton.setMessage(TotemLumenVideoSettingsScreen.rendererEnabledLabel());
            giQualityButton.setMessage(TotemLumenVideoSettingsScreen.giQualityLabel());
            shadowQualityButton.setMessage(TotemLumenVideoSettingsScreen.shadowQualityLabel());
            rayDistanceButton.setMessage(TotemLumenVideoSettingsScreen.rayDistanceLabel());
            internalResolutionButton.setMessage(TotemLumenVideoSettingsScreen.internalResolutionLabel());
            reflectionsEnabledButton.setMessage(TotemLumenVideoSettingsScreen.reflectionsEnabledLabel());
            waterReflectionsButton.setMessage(TotemLumenVideoSettingsScreen.waterReflectionsLabel());
            reflectionBouncesButton.setMessage(TotemLumenVideoSettingsScreen.reflectionBouncesLabel());
            reflectionDistanceButton.setMessage(TotemLumenVideoSettingsScreen.reflectionDistanceLabel());
            temporalQualityButton.setMessage(TotemLumenVideoSettingsScreen.temporalQualityLabel());
            denoiseQualityButton.setMessage(TotemLumenVideoSettingsScreen.denoiseQualityLabel());

            giQualityButton.active = rendererEnabled;
            shadowQualityButton.active = rendererEnabled;
            rayDistanceButton.active = rendererEnabled;
            internalResolutionButton.active = rendererEnabled;
            reflectionsEnabledButton.active = rendererEnabled;
            temporalQualityButton.active = rendererEnabled;
            denoiseQualityButton.active = rendererEnabled;

            waterReflectionsButton.active = reflectionsEnabled;
            reflectionBouncesButton.active = reflectionsEnabled;
            reflectionDistanceButton.active = reflectionsEnabled;

            for (AbstractWidget widget : vanillaRendererWidgets) {
                widget.active = !rendererEnabled;
            }
        }
    }
}
