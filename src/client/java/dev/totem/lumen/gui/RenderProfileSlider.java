package dev.totem.lumen.gui;

import dev.totem.lumen.render.ProfilePreparation;
import dev.totem.lumen.render.RendererSettings.RenderProfile;
import net.minecraft.network.chat.CommonComponents;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import dev.totem.lumen.render.RendererSettings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** The thumb follows the effective renderer while the saved preference prepares. */
final class RenderProfileSlider extends RendererSettingSlider {
    static final List<RenderProfile> BETA_PROFILES = List.of(RenderProfile.TOTEM_LUMEN, RenderProfile.RASTER_RAY, RenderProfile.LUMIA);

    private final RenderProfile initialProfile;
    private final Runnable profileChanged;
    private final EnumMap<RenderProfile, ProfilePreparation.Status> lastStatuses = new EnumMap<>(RenderProfile.class);

    RenderProfileSlider(int x, int y, int width, Runnable profileChanged) {
        super(x, y, width, 20, RendererSettings.availableProfiles().size() - 1,
                () -> RendererSettings.availableProfiles().indexOf(RendererSettings.renderProfile()),
                RendererSettings::setRenderProfile,
                index -> TotemLumenVideoSettingsScreen.renderProfileLabel(RendererSettings.availableProfiles().get(index)));
        this.initialProfile = RendererSettings.renderProfile();
        this.profileChanged = profileChanged;
    }

    static Component preparationMessage(RenderProfile profile) {
        String name = switch (profile) {
            case TOTEM_LUMEN -> "Totem Lumen";
            case RASTER_RAY -> "Raster Ray";
            case LUMIA -> "Lumia";
            default -> throw new IllegalArgumentException("No compilation status for " + profile);
        };
        String state = ProfilePreparation.status(profile).name().toLowerCase(Locale.ROOT);
        return Component.translatable("screen.totem-lumen.profile_status." + state, name);
    }

    static Component preparationSummary() {
        int ready = 0;
        for (var profile : BETA_PROFILES) if (ProfilePreparation.ready(profile)) ready++;
        return Component.translatable("screen.totem-lumen.profile_status.summary", ready, BETA_PROFILES.size());
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (RendererSettings.betaRendering()) {
            boolean changed = false;
            for (var profile : BETA_PROFILES) {
                var status = ProfilePreparation.status(profile);
                if (lastStatuses.put(profile, status) != status) changed = true;
            }
            if (changed) {
                refreshValue();
                var lines = BETA_PROFILES.stream().map(RenderProfileSlider::preparationMessage).toList();
                setTooltip(Tooltip.create(CommonComponents.joinLines(lines)));
            }
        }
        super.extractWidgetRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (canChangeValue && (event.isLeft() || event.isRight())) {
            var profiles = RendererSettings.availableProfiles();
            int step = event.isLeft() ? -1 : 1;
            for (int index = profiles.indexOf(RendererSettings.renderProfile()) + step;
                 index >= 0 && index < profiles.size(); index += step) {
                if (ProfilePreparation.ready(profiles.get(index))) {
                    setValue((double) index / (profiles.size() - 1));
                    break;
                }
            }
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
