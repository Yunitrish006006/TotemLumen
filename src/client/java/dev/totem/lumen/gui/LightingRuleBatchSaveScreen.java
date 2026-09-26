package dev.totem.lumen.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Saves staged block rules to the pack selected in the preview, or creates a new one. */
public final class LightingRuleBatchSaveScreen extends Screen {
    private final ServerLightingPreviewScreen parent;
    private final List<LightingRuleDraft> drafts;
    private final long expectedRevision;
    private EditBox name;
    private Button save;
    private final String selectedPackId;
    private Component status = Component.empty();
    private boolean busy;
    private int left;
    private int panelWidth;

    public LightingRuleBatchSaveScreen(ServerLightingPreviewScreen parent,
                                       List<LightingRuleDraft> drafts, long expectedRevision,
                                       String selectedPackId) {
        super(Component.translatable("screen.totem-lumen.batch_save.title"));
        this.parent = parent;
        this.drafts = List.copyOf(drafts);
        this.expectedRevision = expectedRevision;
        this.selectedPackId = selectedPackId;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(320, Math.max(190, width - 24));
        left = (width - panelWidth) / 2;
        name = new EditBox(font, left, 105, panelWidth, 20,
                Component.translatable("screen.totem-lumen.rule_editor.pack_name"));
        name.setMaxLength(32);
        name.setHint(Component.translatable("screen.totem-lumen.rule_editor.pack_name"));
        name.setValue("lighting-" + Long.toString(System.currentTimeMillis(), 36));
        addRenderableWidget(name);
        name.visible = selectedPackId.isEmpty();
        save = addRenderableWidget(Button.builder(
                Component.translatable(selectedPackId.isEmpty()
                        ? "screen.totem-lumen.batch_save.save"
                        : "screen.totem-lumen.batch_save.update"), ignored -> save())
                .bounds(left, height - 53, panelWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),
                ignored -> onClose()).bounds(left, height - 27, panelWidth, 20).build());
        save.active = ClientLightingPackManager.canManage(minecraft) && !drafts.isEmpty();
    }

    private Component targetLabel() {
        if (selectedPackId.isEmpty()) {
            return Component.translatable("screen.totem-lumen.batch_save.target_new");
        }
        var summary = ClientServerLightingViewState.summary();
        if (summary == null) return Component.literal(selectedPackId);
        return summary.enabledPacks().stream().filter(pack -> pack.id().equals(selectedPackId))
                .findFirst().<Component>map(pack -> Component.translatable(
                        "screen.totem-lumen.batch_save.target_existing", pack.title()))
                .orElseGet(() -> Component.literal(selectedPackId));
    }

    private void save() {
        if (busy) return;
        busy = true;
        save.active = false;
        boolean createNew = selectedPackId.isEmpty();
        status = Component.translatable(createNew ? "screen.totem-lumen.rule_editor.saving"
                : "screen.totem-lumen.batch_save.updating");
        java.util.function.Consumer<Throwable> completed = problem -> {
                    busy = false;
                    if (problem == null) {
                        parent.saved(createNew);
                        minecraft.gui.setScreen(parent);
                    } else {
                        status = Component.literal(problem.toString());
                        save.active = true;
                    }
                };
        if (createNew) {
            ClientLightingPackManager.saveAs(minecraft, name.getValue(), drafts, expectedRevision,
                    completed);
        } else {
            ClientLightingPackManager.saveToExisting(minecraft, selectedPackId, drafts,
                    expectedRevision, completed);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
        graphics.text(font, Component.translatable("screen.totem-lumen.batch_save.count", drafts.size()),
                left, 35, 0xFFFFFF55);
        var summary = ClientServerLightingViewState.summary();
        if (summary != null) {
            graphics.text(font, Component.translatable("screen.totem-lumen.server_preview.enabled_packs",
                    summary.enabledPackCount()), left, 53, 0xFFAADDDD);
        }
        graphics.textWithWordWrap(font, targetLabel(), left, 72, panelWidth, 0xFFFFFFFF);
        if (!status.getString().isEmpty()) {
            graphics.textWithWordWrap(font, status, left, 133, panelWidth, 0xFFFFAA66);
        }
    }

    @Override
    public void onClose() {
        if (!busy) minecraft.gui.setScreen(parent);
    }
}
