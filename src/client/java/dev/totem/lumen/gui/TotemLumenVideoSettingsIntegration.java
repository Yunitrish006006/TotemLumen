package dev.totem.lumen.gui;

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
                    // Rebuild the native Video Settings screen so its profile-dependent option
                    // arrays are reconstructed instead of leaving disabled/blank stale rows.
                    screen.rebuildWidgets();
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

    private static Button settingButton(Component label, Runnable action) {
        final Button[] self = new Button[1];
        Button button = Button.builder(label, ignored -> {
            action.run();
            Button current = self[0];
            if (current != null) {
                // Individual labels are refreshed by a lightweight screen rebuild. This also keeps
                // dependent reflection controls in sync without duplicating per-button state logic.
                Minecraft client = Minecraft.getInstance();
                if (client.screen instanceof VideoSettingsScreen videoSettings) {
                    videoSettings.rebuildWidgets();
                }
            }
        }).bounds(0, 0, 150, 20).build();
        self[0] = button;
        return button;
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
