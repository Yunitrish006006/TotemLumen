package dev.totem.lumen.gui;

import dev.totem.lumen.mixin.OptionsSubScreenAccessor;
import dev.totem.lumen.render.RendererSettings;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;

/**
 * Integrates the active rendering profile directly into Minecraft's Video Settings screen.
 *
 * <p>The profile selector is always visible. Minecraft profile uses vanilla world-render controls.
 * Totem Lumen profile replaces those controls with Totem-specific RT controls while shared display
 * and scene-availability options remain supplied by Minecraft.</p>
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

            installProfileControls(client, videoSettings, optionsList);
        });
    }

    private static void installProfileControls(
            Minecraft client,
            VideoSettingsScreen screen,
            OptionsList list
    ) {
        list.addHeader(Component.translatable("screen.totem-lumen.profile_section"));

        Button profileButton = Button.builder(
                TotemLumenVideoSettingsScreen.renderProfileLabel(),
                ignored -> {
                    RendererSettings.cycleRenderProfile();
                    reopenVideoSettings(client, screen);
                }
        ).bounds(0, 0, 150, 20).build();

        Button diagnosticsButton = Button.builder(
                Component.translatable("screen.totem-lumen.diagnostics"),
                ignored -> client.gui.setScreen(new TotemLumenDiagnosticsScreen(screen))
        ).bounds(0, 0, 150, 20).build();

        list.addSmall(profileButton, diagnosticsButton);

        if (!RendererSettings.rendererEnabled()) {
            return;
        }

        list.addHeader(Component.translatable("screen.totem-lumen.video_section"));

        Button giQualityButton = settingButton(
                TotemLumenVideoSettingsScreen.giQualityLabel(),
                RendererSettings::cycleGiQuality
        );
        Button shadowQualityButton = settingButton(
                TotemLumenVideoSettingsScreen.shadowQualityLabel(),
                RendererSettings::cycleShadowQuality
        );
        list.addSmall(giQualityButton, shadowQualityButton);

        Button rayDistanceButton = settingButton(
                TotemLumenVideoSettingsScreen.rayDistanceLabel(),
                RendererSettings::cycleRayDistance
        );
        Button internalResolutionButton = settingButton(
                TotemLumenVideoSettingsScreen.internalResolutionLabel(),
                RendererSettings::cycleInternalResolution
        );
        list.addSmall(rayDistanceButton, internalResolutionButton);

        Button reflectionsEnabledButton = settingButton(
                TotemLumenVideoSettingsScreen.reflectionsEnabledLabel(),
                RendererSettings::toggleReflectionsEnabled
        );
        Button waterReflectionsButton = settingButton(
                TotemLumenVideoSettingsScreen.waterReflectionsLabel(),
                RendererSettings::toggleWaterReflections
        );
        list.addSmall(reflectionsEnabledButton, waterReflectionsButton);

        Button reflectionBouncesButton = settingButton(
                TotemLumenVideoSettingsScreen.reflectionBouncesLabel(),
                RendererSettings::cycleReflectionBounces
        );
        Button reflectionDistanceButton = settingButton(
                TotemLumenVideoSettingsScreen.reflectionDistanceLabel(),
                RendererSettings::cycleReflectionDistance
        );
        list.addSmall(reflectionBouncesButton, reflectionDistanceButton);

        Button temporalQualityButton = settingButton(
                TotemLumenVideoSettingsScreen.temporalQualityLabel(),
                RendererSettings::cycleTemporalQuality
        );
        Button denoiseQualityButton = settingButton(
                TotemLumenVideoSettingsScreen.denoiseQualityLabel(),
                RendererSettings::cycleDenoiseQuality
        );
        list.addSmall(temporalQualityButton, denoiseQualityButton);

        refreshTotemControls(
                giQualityButton,
                shadowQualityButton,
                rayDistanceButton,
                internalResolutionButton,
                reflectionsEnabledButton,
                waterReflectionsButton,
                reflectionBouncesButton,
                reflectionDistanceButton,
                temporalQualityButton,
                denoiseQualityButton
        );
    }

    private static Button settingButton(
            Minecraft client,
            VideoSettingsScreen screen,
            Component label,
            Runnable action
    ) {
        return Button.builder(label, ignored -> {
            action.run();
            reopenVideoSettings(client, screen);
        }).bounds(0, 0, 150, 20).build();
    }

    private static void reopenVideoSettings(
            Minecraft client,
            VideoSettingsScreen screen
    ) {
        OptionsSubScreenAccessor accessor = (OptionsSubScreenAccessor) screen;
        client.gui.setScreen(new VideoSettingsScreen(
                accessor.totemLumen$getLastScreen(),
                client,
                accessor.totemLumen$getOptions()
        ));
    }

    private static void refreshTotemControls(
            Button giQualityButton,
            Button shadowQualityButton,
            Button rayDistanceButton,
            Button internalResolutionButton,
            Button reflectionsEnabledButton,
            Button waterReflectionsButton,
            Button reflectionBouncesButton,
            Button reflectionDistanceButton,
            Button temporalQualityButton,
            Button denoiseQualityButton
    ) {
        boolean reflectionsEnabled = RendererSettings.reflectionsEnabled();

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

        waterReflectionsButton.active = reflectionsEnabled;
        reflectionBouncesButton.active = reflectionsEnabled;
        reflectionDistanceButton.active = reflectionsEnabled;
    }
}
