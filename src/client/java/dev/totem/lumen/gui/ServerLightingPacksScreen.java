package dev.totem.lumen.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** Two-column, local-only data-pack selector; right column is ordered highest priority first. */
public final class ServerLightingPacksScreen extends Screen {
    private final Screen parent;
    private final List<Button> availableRows = new ArrayList<>();
    private final List<Button> selectedRows = new ArrayList<>();
    private List<ClientLightingPackManager.PackInfo> packs = List.of();
    private List<ClientLightingPackManager.PackInfo> available = List.of();
    private List<ClientLightingPackManager.PackInfo> selected = List.of();
    private Button add;
    private Button remove;
    private Button up;
    private Button down;
    private Button availablePrevious;
    private Button availableNext;
    private Button selectedPrevious;
    private Button selectedNext;
    private Button openFolder;
    private Button refreshButton;
    private int availablePage;
    private int selectedPage;
    private String chosenAvailable;
    private String chosenSelected;
    private boolean busy;
    private Component status = Component.empty();
    private int left;
    private int panelWidth;
    private int right;

    public ServerLightingPacksScreen(Screen parent) {
        super(Component.translatable("screen.totem-lumen.server_packs.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        availableRows.clear();
        selectedRows.clear();
        int widthUsed = Math.min(540, width - 24);
        left = (width - widthUsed) / 2;
        panelWidth = (widthUsed - 40) / 2;
        right = left + panelWidth + 40;
        int visible = Math.max(1, Math.min(8, (height - 170) / 24));
        for (int index = 0; index < visible; index++) {
            final int slot = index;
            availableRows.add(addRenderableWidget(Button.builder(Component.empty(), ignored -> chooseAvailable(slot))
                    .bounds(left + 3, 77 + index * 24, panelWidth - 6, 20).build()));
            selectedRows.add(addRenderableWidget(Button.builder(Component.empty(), ignored -> chooseSelected(slot))
                    .bounds(right + 3, 77 + index * 24, panelWidth - 6, 20).build()));
        }

        int centerX = left + panelWidth + 8;
        int arrowsY = Math.min(81, height - 140);
        add = addRenderableWidget(Button.builder(Component.literal("▶"), ignored -> change(
                callback -> ClientLightingPackManager.setEnabled(minecraft, chosenAvailable, true, callback)))
                .bounds(centerX, arrowsY, 24, 20).build());
        remove = addRenderableWidget(Button.builder(Component.literal("◀"), ignored -> change(
                callback -> ClientLightingPackManager.setEnabled(minecraft, chosenSelected, false, callback)))
                .bounds(centerX, arrowsY + 24, 24, 20).build());
        up = addRenderableWidget(Button.builder(Component.literal("▲"), ignored -> change(
                callback -> ClientLightingPackManager.move(minecraft, chosenSelected, true, callback)))
                .bounds(centerX, arrowsY + 48, 24, 20).build());
        down = addRenderableWidget(Button.builder(Component.literal("▼"), ignored -> change(
                callback -> ClientLightingPackManager.move(minecraft, chosenSelected, false, callback)))
                .bounds(centerX, arrowsY + 72, 24, 20).build());
        add.setTooltip(Tooltip.create(Component.translatable("screen.totem-lumen.server_packs.add")));
        remove.setTooltip(Tooltip.create(Component.translatable("screen.totem-lumen.server_packs.remove")));
        up.setTooltip(Tooltip.create(Component.translatable("screen.totem-lumen.server_packs.move_up")));
        down.setTooltip(Tooltip.create(Component.translatable("screen.totem-lumen.server_packs.move_down")));

        availablePrevious = pageButton(left + 3, height - 85, false, false);
        availableNext = pageButton(left + panelWidth - 23, height - 85, false, true);
        selectedPrevious = pageButton(right + 3, height - 85, true, false);
        selectedNext = pageButton(right + panelWidth - 23, height - 85, true, true);

        int half = (widthUsed - 4) / 2;
        openFolder = addRenderableWidget(Button.builder(
                Component.translatable("screen.totem-lumen.server_packs.open_folder"),
                ignored -> openFolder()).bounds(left, height - 55, half, 20).build());
        refreshButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.totem-lumen.server_packs.refresh"),
                ignored -> refresh()).bounds(left + half + 4, height - 55, half, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                ignored -> onClose()).bounds(left, height - 29, widthUsed, 20).build());
        refresh();
    }

    private Button pageButton(int x, int y, boolean rightColumn, boolean forward) {
        return addRenderableWidget(Button.builder(Component.literal(forward ? "▶" : "◀"), ignored -> {
            if (rightColumn) selectedPage = Math.max(0, selectedPage + (forward ? 1 : -1));
            else availablePage = Math.max(0, availablePage + (forward ? 1 : -1));
            updateRows();
        }).bounds(x, y, 20, 20).build());
    }

    private void refresh() {
        if (!ClientLightingPackManager.canManage(minecraft)) {
            packs = List.of();
            status = Component.translatable("screen.totem-lumen.server_packs.local_only");
            updateRows();
            return;
        }
        busy = true;
        status = Component.translatable("screen.totem-lumen.server_packs.loading");
        updateRows();
        ClientLightingPackManager.list(minecraft, result -> {
            packs = result;
            busy = false;
            status = Component.translatable("screen.totem-lumen.server_packs.order_hint");
            updateRows();
        }, problem -> {
            busy = false;
            status = Component.literal(problem.toString());
            updateRows();
        });
    }

    private void chooseAvailable(int slot) {
        int index = availablePage * availableRows.size() + slot;
        if (index >= available.size()) return;
        chosenAvailable = available.get(index).id();
        chosenSelected = null;
        updateRows();
    }

    private void chooseSelected(int slot) {
        int index = selectedPage * selectedRows.size() + slot;
        if (index >= selected.size()) return;
        chosenSelected = selected.get(index).id();
        chosenAvailable = null;
        updateRows();
    }

    private void change(Consumer<Consumer<Throwable>> operation) {
        if (busy) return;
        busy = true;
        status = Component.translatable("screen.totem-lumen.server_packs.applying");
        updateRows();
        operation.accept(problem -> {
            if (problem == null) {
                chosenAvailable = null;
                chosenSelected = null;
                refresh();
            } else {
                busy = false;
                status = Component.literal(problem.toString());
                updateRows();
            }
        });
    }

    private void openFolder() {
        try {
            ClientLightingPackManager.openFolder(minecraft);
            status = Component.translatable("screen.totem-lumen.server_packs.folder_opened");
        } catch (Exception problem) {
            status = Component.literal(problem.toString());
        }
    }

    private void updateRows() {
        boolean manageable = ClientLightingPackManager.canManage(minecraft);
        available = packs.stream().filter(pack -> !pack.enabled())
                .sorted(Comparator.comparing(ClientLightingPackManager.PackInfo::id)).toList();
        selected = packs.stream().filter(ClientLightingPackManager.PackInfo::enabled)
                .sorted(Comparator.comparingInt(ClientLightingPackManager.PackInfo::order).reversed())
                .toList();
        availablePage = Math.min(availablePage, Math.max(0, (available.size() - 1) / availableRows.size()));
        selectedPage = Math.min(selectedPage, Math.max(0, (selected.size() - 1) / selectedRows.size()));
        updateColumn(availableRows, available, availablePage, chosenAvailable, manageable);
        updateColumn(selectedRows, selected, selectedPage, chosenSelected, manageable);

        if (add != null) add.active = manageable && !busy && find(available, chosenAvailable) >= 0;
        int selectedIndex = find(selected, chosenSelected);
        if (remove != null) remove.active = manageable && !busy && selectedIndex >= 0
                && !selected.get(selectedIndex).required();
        if (up != null) up.active = manageable && !busy && selectedIndex > 0;
        if (down != null) down.active = manageable && !busy && selectedIndex >= 0
                && selectedIndex + 1 < selected.size();
        if (availablePrevious != null) availablePrevious.active = manageable && availablePage > 0;
        if (availableNext != null) availableNext.active = manageable
                && (availablePage + 1) * availableRows.size() < available.size();
        if (selectedPrevious != null) selectedPrevious.active = manageable && selectedPage > 0;
        if (selectedNext != null) selectedNext.active = manageable
                && (selectedPage + 1) * selectedRows.size() < selected.size();
        if (openFolder != null) openFolder.active = manageable && !busy;
        if (refreshButton != null) refreshButton.active = manageable && !busy;
    }

    private void updateColumn(List<Button> rows, List<ClientLightingPackManager.PackInfo> column,
                              int page, String chosen, boolean manageable) {
        for (int slot = 0; slot < rows.size(); slot++) {
            Button row = rows.get(slot);
            int index = page * rows.size() + slot;
            row.visible = manageable && index < column.size();
            row.active = row.visible && !busy;
            if (row.visible) {
                ClientLightingPackManager.PackInfo pack = column.get(index);
                Component name = name(pack);
                row.setMessage(pack.id().equals(chosen)
                        ? Component.translatable("screen.totem-lumen.server_packs.chosen", name)
                        : name);
                row.setTooltip(Tooltip.create(Component.literal(name.getString() + "\n" + pack.id())));
            }
        }
    }

    private static int find(List<ClientLightingPackManager.PackInfo> column, String id) {
        if (id == null) return -1;
        for (int index = 0; index < column.size(); index++) {
            if (column.get(index).id().equals(id)) return index;
        }
        return -1;
    }

    private static Component name(ClientLightingPackManager.PackInfo pack) {
        if (pack.id().equals(ClientLightingPackManager.DEFAULT_PACK_ID))
            return Component.translatable("pack.totem-lumen.default_lighting");
        if (pack.id().equals(ClientLightingPackManager.TEST_PACK_ID))
            return Component.translatable("pack.totem-lumen.double_light_test");
        return Component.literal(pack.title());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(left, 72, left + panelWidth, height - 94, 0x66000000);
        graphics.fill(right, 72, right + panelWidth, height - 94, 0x66000000);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 13, 0xFFFFFFFF);
        graphics.centeredText(font, status, width / 2, 31, 0xFFCCCCCC);
        graphics.centeredText(font, Component.translatable("screen.totem-lumen.server_packs.available"),
                left + panelWidth / 2, 56, 0xFFFFFFFF);
        graphics.centeredText(font, Component.translatable("screen.totem-lumen.server_packs.selected"),
                right + panelWidth / 2, 56, 0xFFFFFFFF);
        graphics.centeredText(font, Component.literal((availablePage + 1) + "/"
                + Math.max(1, (available.size() + availableRows.size() - 1) / availableRows.size())),
                left + panelWidth / 2, height - 81, 0xFFAAAAAA);
        graphics.centeredText(font, Component.literal((selectedPage + 1) + "/"
                + Math.max(1, (selected.size() + selectedRows.size() - 1) / selectedRows.size())),
                right + panelWidth / 2, height - 81, 0xFFAAAAAA);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
