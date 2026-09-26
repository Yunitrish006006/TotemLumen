package dev.totem.lumen.gui;

import dev.totem.lumen.network.ServerLightingViewPackets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Read-only server-reported enabled packs; no selection means the effective composition. */
public final class ServerLightingPackSelectionScreen extends Screen {
    private final ServerLightingPreviewScreen parent;
    private final List<Button> rows = new ArrayList<>();
    private Button previous;
    private Button next;
    private int page;
    private int left;
    private int panelWidth;
    private long seenRevision = -1L;

    public ServerLightingPackSelectionScreen(ServerLightingPreviewScreen parent) {
        super(Component.translatable("screen.totem-lumen.pack_selection.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rows.clear();
        panelWidth = Math.min(420, Math.max(200, width - 24));
        left = (width - panelWidth) / 2;
        int visible = Math.max(2, Math.min(9, (height - 146) / 24));
        for (int slot = 0; slot < visible; slot++) {
            final int index = slot;
            rows.add(addRenderableWidget(Button.builder(Component.empty(), ignored -> choose(index))
                    .bounds(left, 76 + slot * 24, panelWidth, 20).build()));
        }
        previous = addRenderableWidget(Button.builder(Component.literal("◀"), ignored -> {
            page = Math.max(0, page - 1);
            updateRows();
        }).bounds(left, height - 55, 38, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal("▶"), ignored -> {
            page++;
            updateRows();
        }).bounds(left + panelWidth - 38, height - 55, 38, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), ignored -> onClose())
                .bounds(left, height - 28, panelWidth, 20).build());
        var summary = ClientServerLightingViewState.summary();
        seenRevision = summary == null ? -1L : summary.revision();
        updateRows();
    }

    @Override
    public void tick() {
        var summary = ClientServerLightingViewState.summary();
        long revision = summary == null ? -1L : summary.revision();
        if (revision != seenRevision) {
            seenRevision = revision;
            updateRows();
        }
    }

    private List<ServerLightingViewPackets.PackInfo> packs() {
        var summary = ClientServerLightingViewState.summary();
        return summary == null ? List.of() : summary.enabledPacks();
    }

    private void choose(int slot) {
        int index = page * rows.size() + slot;
        if (index == 0) {
            parent.selectPack("");
        } else if (index - 1 < packs().size()) {
            parent.selectPack(packs().get(index - 1).id());
        } else return;
        minecraft.gui.setScreen(parent);
    }

    private void updateRows() {
        List<ServerLightingViewPackets.PackInfo> packs = packs();
        int total = packs.size() + 1;
        page = Math.min(page, (total - 1) / rows.size());
        for (int slot = 0; slot < rows.size(); slot++) {
            int index = page * rows.size() + slot;
            Button row = rows.get(slot);
            row.visible = index < total;
            row.active = row.visible;
            if (index == 0 && row.visible) {
                row.setMessage(Component.translatable("screen.totem-lumen.pack_selection.combined"));
                row.setTooltip(Tooltip.create(Component.translatable(
                        "screen.totem-lumen.pack_selection.combined_hint")));
            } else if (row.visible) {
                var pack = packs.get(index - 1);
                row.setMessage(Component.literal(pack.title()));
                row.setTooltip(Tooltip.create(pack.hasTuning()
                        ? Component.translatable("screen.totem-lumen.pack_selection.tuning_tooltip",
                                pack.id(), pack.brightnessMultiplier(), pack.attenuationMultiplier())
                        : Component.literal(pack.id())));
            }
        }
        previous.active = page > 0;
        next.active = (page + 1) * rows.size() < total;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 13, 0xFFFFFFFF);
        var summary = ClientServerLightingViewState.summary();
        if (summary != null) {
            graphics.text(font, Component.translatable("screen.totem-lumen.pack_selection.enabled",
                    summary.enabledPackCount()), left, 35, 0xFFAADDDD);
            if (summary.enabledPackCount() > summary.enabledPacks().size()) {
                graphics.text(font, Component.translatable("screen.totem-lumen.pack_selection.truncated"),
                        left, 50, 0xFFFFAA66);
            }
        }
        graphics.centeredText(font, Component.literal((page + 1) + "/"
                + Math.max(1, (packs().size() + rows.size()) / rows.size())),
                width / 2, height - 50, 0xFFAAAAAA);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
