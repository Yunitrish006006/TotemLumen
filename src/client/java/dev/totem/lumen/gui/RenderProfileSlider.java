package dev.totem.lumen.gui;

import dev.totem.lumen.integration.LumiaPreparation;
import dev.totem.lumen.render.RendererSettings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Reflects the effective profile, including a saved Lumia preference becoming ready. */
final class RenderProfileSlider extends RendererSettingSlider {
    private static final Component IDLE = Component.translatable("screen.totem-lumen.lumia_status.idle");
    private static final Component PREPARING = Component.translatable("screen.totem-lumen.lumia_status.preparing");
    private static final Component READY = Component.translatable("screen.totem-lumen.lumia_status.ready");
    private static final Component FAILED = Component.translatable("screen.totem-lumen.lumia_status.failed");

    private final RendererSettings.RenderProfile initialProfile;
    private final Runnable profileChanged;
    private Component lastStatus;

    RenderProfileSlider(int x, int y, int width, Runnable profileChanged) {
        super(x, y, width, 20, RendererSettings.availableProfiles().size() - 1,
                () -> RendererSettings.availableProfiles().indexOf(RendererSettings.renderProfile()),
                RendererSettings::setRenderProfile,
                index -> TotemLumenVideoSettingsScreen.renderProfileLabel(RendererSettings.availableProfiles().get(index)));
        this.initialProfile = RendererSettings.renderProfile();
        this.profileChanged = profileChanged;
    }

    static Component preparationMessage() {
        return switch (LumiaPreparation.status()) {
            case IDLE -> IDLE;
            case PREPARING -> PREPARING;
            case READY -> READY;
            case FAILED -> FAILED;
        };
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (RendererSettings.betaRendering()) {
            Component status = preparationMessage();
            if (status != lastStatus) {
                refreshValue();
                setTooltip(Tooltip.create(status));
                lastStatus = status;
            }
        }
        super.extractWidgetRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (canChangeValue && (event.isLeft() || event.isRight())) {
            var profiles = RendererSettings.availableProfiles();
            int index = profiles.indexOf(RendererSettings.renderProfile()) + (event.isLeft() ? -1 : 1);
            setValue((double) index / (profiles.size() - 1));
            refreshControlsIfChanged();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onRelease(net.minecraft.client.input.MouseButtonEvent event) {
        super.onRelease(event);
        refreshControlsIfChanged();
    }

    private void refreshControlsIfChanged() {
        if (RendererSettings.renderProfile() != initialProfile) profileChanged.run();
    }
}
