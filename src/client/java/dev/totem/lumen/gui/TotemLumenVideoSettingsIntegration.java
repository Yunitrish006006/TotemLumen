package dev.totem.lumen.gui;

import dev.totem.lumen.mixin.OptionsSubScreenAccessor;
import dev.totem.lumen.render.RendererSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

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

    public static void addDisplayControls(VideoSettingsScreen screen, OptionsList list) {
        Minecraft client = Minecraft.getInstance();
        Button preview = Button.builder(
                Component.translatable("screen.totem-lumen.server_preview.entry"),
                ignored -> client.gui.setScreen(new ServerLightingPreviewScreen(screen))
        ).bounds(0, 0, 150, 20).build();
        Button packs = Button.builder(
                Component.translatable("screen.totem-lumen.server_packs.entry"),
                ignored -> client.gui.setScreen(new ServerLightingPacksScreen(screen))
        ).bounds(0, 0, 150, 20).build();
        list.addSmall(preview, packs);
    }

    public static void addQualityControls(
            VideoSettingsScreen screen,
            OptionsList list
    ) {
        Minecraft client = Minecraft.getInstance();

        RendererSettingSlider profileSlider = new RendererSettingSlider(
                0, 0, 150, 20,
                RendererSettings.RenderProfile.values().length - 1,
                () -> RendererSettings.renderProfile().ordinal(),
                RendererSettings::setRenderProfile,
                index -> TotemLumenVideoSettingsScreen.renderProfileLabel(
                        RendererSettings.RenderProfile.values()[index]
                ),
                () -> reopenVideoSettings(client, screen)
        );

        Button diagnosticsButton = Button.builder(
                Component.translatable("screen.totem-lumen.diagnostics"),
                ignored -> client.gui.setScreen(new TotemLumenDiagnosticsScreen(screen))
        ).bounds(0, 0, 150, 20).build();

        list.addSmall(profileSlider, diagnosticsButton);

        if (!RendererSettings.rendererEnabled()) {
            return;
        }

        RendererSettingSlider giQualitySlider = settingSlider(
                RendererSettings.GiQuality.values().length - 1,
                () -> RendererSettings.giQuality().ordinal(),
                RendererSettings::setGiQuality,
                index -> TotemLumenVideoSettingsScreen.giQualityLabel(RendererSettings.GiQuality.values()[index])
        );
        RendererSettingSlider shadowQualitySlider = settingSlider(
                RendererSettings.Quality.values().length - 1,
                () -> RendererSettings.shadowQuality().ordinal(),
                RendererSettings::setShadowQuality,
                index -> TotemLumenVideoSettingsScreen.shadowQualityLabel(RendererSettings.Quality.values()[index])
        );
        list.addSmall(giQualitySlider, shadowQualitySlider);

        RendererSettingSlider rayDistanceSlider = settingSlider(
                3,
                () -> rayDistanceIndex(RendererSettings.rayDistance()),
                RendererSettings::setRayDistanceIndex,
                index -> TotemLumenVideoSettingsScreen.rayDistanceLabel(new int[]{32, 64, 96, 128}[index])
        );
        RendererSettingSlider internalResolutionSlider = settingSlider(
                RendererSettings.InternalResolution.values().length - 1,
                () -> RendererSettings.internalResolution().ordinal(),
                RendererSettings::setInternalResolution,
                index -> TotemLumenVideoSettingsScreen.internalResolutionLabel(RendererSettings.InternalResolution.values()[index])
        );
        list.addSmall(rayDistanceSlider, internalResolutionSlider);

        AbstractWidget[] reflectionControls = new AbstractWidget[3];
        Button reflectionsEnabledButton = settingButton(
                TotemLumenVideoSettingsScreen::reflectionsEnabledLabel,
                RendererSettings::toggleReflectionsEnabled,
                () -> refreshReflectionControls(reflectionControls)
        );
        Button waterReflectionsButton = settingButton(
                TotemLumenVideoSettingsScreen::waterReflectionsLabel,
                RendererSettings::toggleWaterReflections
        );
        list.addSmall(reflectionsEnabledButton, waterReflectionsButton);

        Button entityRayTracingButton = settingButton(
                TotemLumenVideoSettingsScreen::entityRayTracingLabel,
                RendererSettings::toggleEntityRayTracing
        );
        Button localLightQualityButton = settingButton(
                TotemLumenVideoSettingsScreen::localLightQualityLabel,
                RendererSettings::cycleLocalLightQuality
        );
        list.addSmall(localLightQualityButton, entityRayTracingButton);

        RendererSettingSlider reflectionBouncesSlider = settingSlider(
                1,
                () -> RendererSettings.reflectionBounces() - 1,
                index -> RendererSettings.setReflectionBounces(index + 1),
                index -> TotemLumenVideoSettingsScreen.reflectionBouncesLabel(index + 1)
        );
        RendererSettingSlider reflectionDistanceSlider = settingSlider(
                2,
                () -> reflectionDistanceIndex(RendererSettings.reflectionDistance()),
                RendererSettings::setReflectionDistanceIndex,
                index -> TotemLumenVideoSettingsScreen.reflectionDistanceLabel(new int[]{16, 32, 64}[index])
        );
        list.addSmall(reflectionBouncesSlider, reflectionDistanceSlider);

        RendererSettingSlider temporalQualitySlider = settingSlider(
                RendererSettings.TemporalQuality.values().length - 1,
                () -> RendererSettings.temporalQuality().ordinal(),
                RendererSettings::setTemporalQuality,
                index -> TotemLumenVideoSettingsScreen.temporalQualityLabel(RendererSettings.TemporalQuality.values()[index])
        );
        RendererSettingSlider denoiseQualitySlider = settingSlider(
                RendererSettings.DenoiseQuality.values().length - 1,
                () -> RendererSettings.denoiseQuality().ordinal(),
                RendererSettings::setDenoiseQuality,
                index -> TotemLumenVideoSettingsScreen.denoiseQualityLabel(RendererSettings.DenoiseQuality.values()[index])
        );
        list.addSmall(temporalQualitySlider, denoiseQualitySlider);

        reflectionControls[0] = reflectionsEnabledButton;
        reflectionControls[1] = reflectionBouncesSlider;
        reflectionControls[2] = reflectionDistanceSlider;

        refreshReflectionControls(reflectionControls);
    }

    private static Button settingButton(Supplier<Component> labelSupplier, Runnable action) {
        return settingButton(labelSupplier, action, () -> {
        });
    }

    private static Button settingButton(
            Supplier<Component> labelSupplier,
            Runnable action,
            Runnable afterAction
    ) {
        Button[] buttonRef = new Button[1];
        buttonRef[0] = Button.builder(labelSupplier.get(), ignored -> {
            action.run();
            buttonRef[0].setMessage(labelSupplier.get());
            afterAction.run();
        }).bounds(0, 0, 150, 20).build();
        return buttonRef[0];
    }

    private static RendererSettingSlider settingSlider(
            int maximumIndex,
            java.util.function.IntSupplier currentIndex,
            java.util.function.IntConsumer applyIndex,
            java.util.function.Function<Integer, Component> label
    ) {
        return new RendererSettingSlider(0, 0, 150, 20, maximumIndex, currentIndex, applyIndex, label);
    }

    private static void refreshReflectionControls(AbstractWidget[] controls) {
        boolean enabled = RendererSettings.reflectionsEnabled();
        for (AbstractWidget control : controls) {
            if (control != null) control.active = enabled;
        }
    }

    private static int rayDistanceIndex(int value) {
        return value <= 32 ? 0 : value <= 64 ? 1 : value <= 96 ? 2 : 3;
    }

    private static int reflectionDistanceIndex(int value) {
        return value <= 16 ? 0 : value <= 32 ? 1 : 2;
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
