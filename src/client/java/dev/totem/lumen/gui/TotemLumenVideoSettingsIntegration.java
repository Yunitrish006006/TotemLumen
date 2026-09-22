package dev.totem.lumen.gui;

import dev.totem.lumen.mixin.OptionsSubScreenAccessor;
import dev.totem.lumen.render.RendererSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;

/**
 * Injects Totem Lumen controls directly into Minecraft's native "Quality & Performance" section.
 *
 * <p>No separate Totem or rendering category is created. The render-profile selector occupies the
 * first row of the native quality section. Minecraft profile keeps vanilla quality options; Totem
 * Lumen profile filters replaced vanilla renderer controls and inserts Totem-specific RT controls
 * in the same section.</p>
 */
public final class TotemLumenVideoSettingsIntegration {
    private TotemLumenVideoSettingsIntegration() {
    }

    /** Retained as a stable client-init entry point; the actual UI injection is mixin-driven. */
    public static void initialize() {
    }

    public static void addQualityControls(
            VideoSettingsScreen screen,
            OptionsList list
    ) {
        Minecraft client = Minecraft.getInstance();

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

        Button giQualityButton = settingButton(
                client,
                screen,
                TotemLumenVideoSettingsScreen.giQualityLabel(),
                RendererSettings::cycleGiQuality
        );
        Button shadowQualityButton = settingButton(
                client,
                screen,
                TotemLumenVideoSettingsScreen.shadowQualityLabel(),
                RendererSettings::cycleShadowQuality
        );
        list.addSmall(giQualityButton, shadowQualityButton);

        Button rayDistanceButton = settingButton(
                client,
                screen,
                TotemLumenVideoSettingsScreen.rayDistanceLabel(),
                RendererSettings::cycleRayDistance
        );
        Button internalResolutionButton = settingButton(
                client,
                screen,
                TotemLumenVideoSettingsScreen.internalResolutionLabel(),
                RendererSettings::cycleInternalResolution
        );
        list.addSmall(rayDistanceButton, internalResolutionButton);

        Button reflectionsEnabledButton = settingButton(
                client,
                screen,
                TotemLumenVideoSettingsScreen.reflectionsEnabledLabel(),
                RendererSettings::toggleReflectionsEnabled
        );
        Button waterReflectionsButton = settingButton(
                client,
                screen,
                TotemLumenVideoSettingsScreen.waterReflectionsLabel(),
                RendererSettings::toggleWaterReflections
        );
        list.addSmall(reflectionsEnabledButton, waterReflectionsButton);

        Button reflectionBouncesButton = settingButton(
                client,
                screen,
                TotemLumenVideoSettingsScreen.reflectionBouncesLabel(),
                RendererSettings::cycleReflectionBounces
        );
        Button reflectionDistanceButton = settingButton(
                client,
                screen,
                TotemLumenVideoSettingsScreen.reflectionDistanceLabel(),
                RendererSettings::cycleReflectionDistance
        );
        list.addSmall(reflectionBouncesButton, reflectionDistanceButton);

        Button temporalQualityButton = settingButton(
                client,
                screen,
                TotemLumenVideoSettingsScreen.temporalQualityLabel(),
                RendererSettings::cycleTemporalQuality
        );
        Button denoiseQualityButton = settingButton(
                client,
                screen,
                TotemLumenVideoSettingsScreen.denoiseQualityLabel(),
                RendererSettings::cycleDenoiseQuality
        );
        list.addSmall(temporalQualityButton, denoiseQualityButton);

        boolean reflectionsEnabled = RendererSettings.reflectionsEnabled();
        waterReflectionsButton.active = reflectionsEnabled;
        reflectionBouncesButton.active = reflectionsEnabled;
        reflectionDistanceButton.active = reflectionsEnabled;
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
}
