package dev.totem.lumen.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Names and commits every staged block rule as one new local-world data pack. */
public final class LightingRuleBatchSaveScreen extends Screen {
    private final ServerLightingPreviewScreen parent;
    private final List<LightingRuleDraft> drafts;
    private final long expectedRevision;
    private EditBox name;
    private Button save;
    private Component status = Component.empty();
    private boolean busy;
    private int left;
    private int panelWidth;

    public LightingRuleBatchSaveScreen(ServerLightingPreviewScreen parent,
                                       List<LightingRuleDraft> drafts, long expectedRevision) {
        super(Component.translatable("screen.totem-lumen.batch_save.title"));
        this.parent = parent;
        this.drafts = List.copyOf(drafts);
        this.expectedRevision = expectedRevision;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(320, Math.max(190, width - 24));
        left = (width - panelWidth) / 2;
        name = new EditBox(font, left, 76, panelWidth, 20,
                Component.translatable("screen.totem-lumen.rule_editor.pack_name"));
        name.setMaxLength(32);
        name.setHint(Component.translatable("screen.totem-lumen.rule_editor.pack_name"));
        name.setValue("lighting-" + Long.toString(System.currentTimeMillis(), 36));
        addRenderableWidget(name);
        save = addRenderableWidget(Button.builder(
                Component.translatable("screen.totem-lumen.batch_save.save"), ignored -> save())
                .bounds(left, height - 53, panelWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),
                ignored -> onClose()).bounds(left, height - 27, panelWidth, 20).build());
        save.active = ClientLightingPackManager.canManage(minecraft) && !drafts.isEmpty();
    }

    private void save() {
        if (busy) return;
        busy = true;
        save.active = false;
        status = Component.translatable("screen.totem-lumen.rule_editor.saving");
        ClientLightingPackManager.saveAs(minecraft, name.getValue(), drafts, expectedRevision,
                problem -> {
                    busy = false;
                    if (problem == null) {
                        parent.saved();
                        minecraft.gui.setScreen(parent);
                    } else {
                        status = Component.literal(problem.toString());
                        save.active = true;
                    }
                });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
        graphics.text(font, Component.translatable("screen.totem-lumen.batch_save.count", drafts.size()),
                left, 48, 0xFFFFFF55);
        if (!status.getString().isEmpty()) {
            graphics.textWithWordWrap(font, status, left, 109, panelWidth, 0xFFFFAA66);
        }
    }

    @Override
    public void onClose() {
        if (!busy) minecraft.gui.setScreen(parent);
    }
}
