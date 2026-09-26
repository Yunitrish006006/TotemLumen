package dev.totem.lumen.gui;

import dev.totem.lumen.network.ServerLightingViewPackets;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-authoritative preview with local-only staged block edits. */
public final class ServerLightingPreviewScreen extends Screen {
    private final Screen parent;
    private final List<Button> rows = new ArrayList<>();
    private EditBox search;
    private Button previous;
    private Button next;
    private Button scrollUp;
    private Button scrollDown;
    private Button editRule;
    private Button saveAll;
    private Button choosePack;
    private final Map<Identifier, LightingRuleDraft> drafts = new LinkedHashMap<>();
    private long draftRevision = -1L;
    private boolean staleDrafts;
    private boolean closePending;
    private Component status = Component.empty();
    private int category = ServerLightingViewPackets.BLOCKS;
    private int pageNumber;
    private int scroll;
    private int left;
    private int panelWidth;
    private int listY;
    private int pendingSearchTicks;
    private int retryTicks;
    private long seenRevision = -1L;
    private ServerLightingViewPackets.Page displayed;
    private ServerLightingViewPackets.Entry selected;
    private String query = "";
    private String selectedPackId = "";

    public ServerLightingPreviewScreen(Screen parent) {
        super(Component.translatable("screen.totem-lumen.server_preview.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rows.clear();
        panelWidth = Math.min(460, Math.max(200, this.width - 24));
        left = (this.width - panelWidth) / 2;
        int gap = 4;
        int tabWidth = (panelWidth - gap * 2) / 3;
        boolean compact = panelWidth < 380;
        int packY = height < 300 ? 74 : compact ? 166 : 116;
        choosePack = this.addRenderableWidget(Button.builder(packLabel(), ignored ->
                minecraft.gui.setScreen(new ServerLightingPackSelectionScreen(this)))
                .bounds(left, packY, panelWidth, 20).build());
        int tabsY = packY + 25;
        this.addRenderableWidget(Button.builder(Component.translatable("screen.totem-lumen.server_preview.blocks"),
                ignored -> switchCategory(ServerLightingViewPackets.BLOCKS))
                .bounds(left, tabsY, tabWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.totem-lumen.server_preview.spawn"),
                ignored -> switchCategory(ServerLightingViewPackets.SPAWN))
                .bounds(left + tabWidth + gap, tabsY, tabWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.totem-lumen.server_preview.dimensions"),
                ignored -> switchCategory(ServerLightingViewPackets.DIMENSIONS))
                .bounds(left + (tabWidth + gap) * 2, tabsY, tabWidth, 20).build());

        search = new EditBox(this.font, left, tabsY + 25, panelWidth, 18,
                Component.translatable("screen.totem-lumen.server_preview.search"));
        search.setMaxLength(64);
        search.setHint(Component.translatable("screen.totem-lumen.server_preview.search"));
        search.setValue(query);
        search.setResponder(value -> {
            cancelCloseWarning();
            query = value;
            pageNumber = 0;
            scroll = 0;
            selected = null;
            pendingSearchTicks = 6;
        });
        this.addRenderableWidget(search);

        listY = tabsY + 49;
        int visibleRows = Math.max(1, Math.min(8, (this.height - listY - 73) / 22));
        for (int index = 0; index < visibleRows; index++) {
            final int slot = index;
            Button row = this.addRenderableWidget(Button.builder(Component.empty(), ignored -> select(slot))
                    .bounds(left + 22, listY + index * 22, panelWidth - 44, 20).build());
            rows.add(row);
        }
        scrollUp = this.addRenderableWidget(Button.builder(Component.literal("▲"), ignored -> moveScroll(-1))
                .bounds(left + panelWidth - 20, listY, 20, 20).build());
        scrollDown = this.addRenderableWidget(Button.builder(Component.literal("▼"), ignored -> moveScroll(1))
                .bounds(left + panelWidth - 20, listY + Math.max(0, visibleRows - 1) * 22,
                        20, 20).build());
        int navY = this.height - 55;
        previous = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.totem-lumen.server_preview.previous"),
                ignored -> movePage(-1)).bounds(left, navY, (panelWidth - 4) / 2, 20).build());
        next = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.totem-lumen.server_preview.next"),
                ignored -> movePage(1)).bounds(left + (panelWidth + 4) / 2, navY,
                (panelWidth - 4) / 2, 20).build());
        int bottomThird = (panelWidth - 8) / 3;
        editRule = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.totem-lumen.rule_editor.entry"), ignored -> {
                    if (selected != null) minecraft.gui.setScreen(new LightingRuleEditorScreen(
                            this, selected, drafts.get(selected.id())));
                }).bounds(left, this.height - 28, bottomThird, 20).build());
        saveAll = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.totem-lumen.server_preview.save_all"), ignored -> {
                    if (!drafts.isEmpty() && !staleDrafts) {
                        minecraft.gui.setScreen(new LightingRuleBatchSaveScreen(this, List.copyOf(drafts.values()),
                                draftRevision));
                    }
                }).bounds(left + bottomThird + 4, this.height - 28, bottomThird, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                ignored -> onClose()).bounds(left + (bottomThird + 4) * 2, this.height - 28,
                panelWidth - (bottomThird + 4) * 2, 20).build());
        updateRows();
        ServerLightingViewPackets.Summary summary = ClientServerLightingViewState.summary();
        if (summary != null) {
            if (!drafts.isEmpty() && summary.revision() != draftRevision) {
                staleDrafts = true;
                status = Component.translatable("screen.totem-lumen.server_preview.stale_drafts");
            }
            if (!selectedPackId.isEmpty() && summary.enabledPacks().stream()
                    .noneMatch(pack -> pack.id().equals(selectedPackId))) selectedPackId = "";
            seenRevision = summary.revision();
            requestPage();
        }
    }

    @Override
    public void tick() {
        ServerLightingViewPackets.Summary summary = ClientServerLightingViewState.summary();
        if (summary != null && summary.revision() != seenRevision) {
            if (!drafts.isEmpty() && summary.revision() != draftRevision) {
                staleDrafts = true;
                status = Component.translatable("screen.totem-lumen.server_preview.stale_drafts");
            }
            seenRevision = summary.revision();
            if (!selectedPackId.isEmpty() && summary.enabledPacks().stream()
                    .noneMatch(pack -> pack.id().equals(selectedPackId))) selectedPackId = "";
            pageNumber = 0;
            scroll = 0;
            selected = null;
            requestPage();
        }
        if (pendingSearchTicks > 0 && --pendingSearchTicks == 0) requestPage();
        if (summary != null && pendingSearchTicks == 0
                && ClientServerLightingViewState.page() == null && ++retryTicks >= 30) {
            retryTicks = 0;
            requestPage();
        }
        ServerLightingViewPackets.Page fresh = ClientServerLightingViewState.page();
        if (fresh != displayed) {
            displayed = fresh;
            scroll = 0;
            updateRows();
        }
    }

    private void switchCategory(int nextCategory) {
        if (category == nextCategory) return;
        cancelCloseWarning();
        category = nextCategory;
        pageNumber = 0;
        scroll = 0;
        selected = null;
        requestPage();
    }

    private void movePage(int delta) {
        cancelCloseWarning();
        pageNumber = Math.max(0, Math.min(341, pageNumber + delta));
        scroll = 0;
        selected = null;
        requestPage();
    }

    private void requestPage() {
        retryTicks = 0;
        ClientServerLightingViewState.request(category, pageNumber, query, selectedPackId);
        displayed = null;
        updateRows();
    }

    private void select(int slot) {
        if (displayed == null) return;
        cancelCloseWarning();
        int index = scroll + slot;
        if (index < displayed.entries().size()) {
            selected = displayed.entries().get(index);
            updateRows();
        }
    }

    private void moveScroll(int delta) {
        if (displayed == null) return;
        cancelCloseWarning();
        scroll = Math.max(0, Math.min(Math.max(0, displayed.entries().size() - rows.size()), scroll + delta));
        updateRows();
    }

    private void updateRows() {
        for (int index = 0; index < rows.size(); index++) {
            Button row = rows.get(index);
            int entryIndex = scroll + index;
            boolean available = displayed != null && entryIndex < displayed.entries().size();
            row.visible = available;
            row.active = available;
            if (available) {
                ServerLightingViewPackets.Entry entry = displayed.entries().get(entryIndex);
                Component label = category == ServerLightingViewPackets.BLOCKS
                        ? Component.literal(blockName(entry) + " · " + entry.id())
                        : Component.literal(entry.id().toString());
                boolean pending = category == ServerLightingViewPackets.BLOCKS
                        && drafts.containsKey(entry.id());
                row.setMessage(pending
                        ? Component.literal("* ").withStyle(ChatFormatting.YELLOW)
                                .append(label.copy().withStyle(ChatFormatting.YELLOW))
                        : label);
                row.setTooltip(Tooltip.create(pending
                        ? Component.translatable("screen.totem-lumen.server_preview.pending_tooltip", entry.id())
                        : Component.literal(entry.id().toString())));
            }
        }
        if (previous != null) previous.active = pageNumber > 0;
        if (next != null) next.active = displayed != null
                && (pageNumber + 1) * ServerLightingViewPackets.PAGE_SIZE < displayed.total();
        if (scrollUp != null) scrollUp.active = scroll > 0;
        if (scrollDown != null) scrollDown.active = displayed != null
                && scroll + rows.size() < displayed.entries().size();
        if (editRule != null) editRule.active = selected != null
                && category == ServerLightingViewPackets.BLOCKS
                && selected.origin() != 2 && !staleDrafts
                && ClientLightingPackManager.canManage(minecraft);
        if (saveAll != null) saveAll.active = !drafts.isEmpty() && !staleDrafts
                && ClientLightingPackManager.canManage(minecraft);
        if (choosePack != null) {
            choosePack.setMessage(packLabel());
            ServerLightingViewPackets.Summary summary = ClientServerLightingViewState.summary();
            if (summary != null) {
                String names = summary.enabledPacks().stream().limit(8)
                        .map(ServerLightingViewPackets.PackInfo::title)
                        .collect(java.util.stream.Collectors.joining("\n"));
                if (summary.enabledPackCount() > 8) names += "\n…";
                choosePack.setTooltip(Tooltip.create(Component.literal(names)));
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 13, 0xFFFFFFFF);
        ServerLightingViewPackets.Summary summary = ClientServerLightingViewState.summary();
        if (summary == null) {
            graphics.centeredText(font, Component.translatable("screen.totem-lumen.server_preview.unavailable"),
                    width / 2, 48, 0xFFFFAA66);
            return;
        }
        graphics.text(font, Component.translatable("screen.totem-lumen.server_preview.revision",
                summary.revision()), left, 29, 0xFFAADDDD);
        int half = panelWidth / 2;
        boolean compact = panelWidth < 380;
        boolean shortScreen = height < 300;
        if (!shortScreen) {
            iconLine(graphics, new ItemStack(Items.ZOMBIE_SPAWN_EGG),
                Component.translatable("screen.totem-lumen.server_preview.mode",
                        summary.spawnMode() == 0 ? "RGB" : "VANILLA"), left, 43);
            iconLine(graphics, new ItemStack(Items.LANTERN),
                Component.translatable("screen.totem-lumen.server_preview.held",
                        onOff(summary.heldEnabled())), compact ? left : left + half,
                compact ? 64 : 43);
            iconLine(graphics, new ItemStack(Items.REPEATER),
                Component.translatable("screen.totem-lumen.server_preview.work",
                        summary.workBudget()), left, compact ? 85 : 66);
            iconLine(graphics, new ItemStack(Items.CLOCK),
                Component.translatable("screen.totem-lumen.server_preview.time",
                        summary.timeBudgetNanos() / 1_000_000.0), compact ? left : left + half,
                compact ? 106 : 66);
        }
        graphics.text(font, Component.translatable("screen.totem-lumen.server_preview.enabled_packs",
                summary.enabledPackCount()), left, shortScreen ? 43 : compact ? 129 : 84, 0xFFAADDDD);
        if (summary.enabledPackCount() > summary.enabledPacks().size()) {
            graphics.text(font, Component.translatable("screen.totem-lumen.server_preview.pack_list_truncated"),
                    left + panelWidth / 2, shortScreen ? 43 : compact ? 129 : 84, 0xFFFFAA66);
        }
        var packTuning = selectedPackId.isEmpty() ? null : summary.enabledPacks().stream()
                .filter(pack -> pack.id().equals(selectedPackId)).findFirst().orElse(null);
        if (packTuning == null || packTuning.hasTuning()) {
            float brightness = packTuning == null ? summary.brightnessMultiplier()
                    : packTuning.brightnessMultiplier();
            float attenuation = packTuning == null ? summary.attenuationMultiplier()
                    : packTuning.attenuationMultiplier();
            graphics.text(font, Component.translatable("screen.totem-lumen.server_preview.tuning",
                    brightness, attenuation), left, shortScreen ? 57 : compact ? 143 : 98,
                    0xFFAADDDD);
        } else {
            graphics.text(font, Component.translatable("screen.totem-lumen.server_preview.no_tuning"),
                    left, shortScreen ? 57 : compact ? 143 : 98, 0xFFAAAAAA);
        }

        if (displayed == null) {
            graphics.text(font, Component.translatable("screen.totem-lumen.server_preview.loading"),
                    left, listY + 5, 0xFFAAAAAA);
        } else {
            for (int index = 0; index < rows.size(); index++) {
                int entryIndex = scroll + index;
                if (entryIndex >= displayed.entries().size()) break;
                graphics.item(icon(displayed.entries().get(entryIndex)), left + 2,
                        listY + index * 22 + 2);
            }
            graphics.text(font, Component.translatable("screen.totem-lumen.server_preview.page",
                    pageNumber + 1, Math.max(1, (displayed.total() + 23) / 24), displayed.total()),
                    left, height - 77, 0xFFAAAAAA);
        }
        if (!drafts.isEmpty()) {
            graphics.text(font, Component.translatable("screen.totem-lumen.server_preview.pending_count",
                    drafts.size()), left, height - 94, 0xFFFFFF55);
        }
        if (!status.getString().isEmpty()) {
            graphics.textWithWordWrap(font, status, left, height - 108, panelWidth, 0xFFFFFF55);
        }
        if (selected != null) drawDetails(graphics, selected);
    }

    private void drawDetails(GuiGraphicsExtractor graphics, ServerLightingViewPackets.Entry entry) {
        int y = listY + rows.size() * 22 + 4;
        if (y + 29 > height - 77) return;
        LightingRuleDraft pending = category == ServerLightingViewPackets.BLOCKS
                ? drafts.get(entry.id()) : null;
        int swatch = pending == null
                ? 0xFF000000 | ((int) (entry.red() * 255) << 16)
                        | ((int) (entry.green() * 255) << 8) | (int) (entry.blue() * 255)
                : 0xFF000000 | (pending.red() << 16) | (pending.green() << 8) | pending.blue();
        graphics.fill(left, y, left + 12, y + 12, swatch);
        graphics.text(font, Component.literal(entry.id().toString()), left + 16, y + 1,
                pending == null ? 0xFFFFFFFF : 0xFFFFFF55);
        Component detail = switch (category) {
            case ServerLightingViewPackets.SPAWN -> Component.translatable(
                    "screen.totem-lumen.server_preview.spawn_detail",
                    origin(entry.origin()),
                    formatRgb(entry.red(), entry.green(), entry.blue()),
                    formatRgb(entry.extraRed(), entry.extraGreen(), entry.extraBlue()));
            case ServerLightingViewPackets.DIMENSIONS -> Component.translatable(
                    "screen.totem-lumen.server_preview.dimension_detail", entry.strength(),
                    onOff(entry.flag()));
            default -> Component.translatable("screen.totem-lumen.server_preview.detail",
                    !selectedPackId.isEmpty() && entry.origin() == 2
                            ? Component.translatable("screen.totem-lumen.server_preview.pack_overridden")
                            : origin(entry.origin()),
                    (pending == null ? entry.strength() : pending.strength()) < 0
                            ? Component.translatable("screen.totem-lumen.server_preview.by_state")
                            : Component.literal(Integer.toString(pending == null
                                    ? entry.strength() : pending.strength())));
        };
        graphics.text(font, detail, left, y + 16, pending == null ? 0xFFCCCCCC : 0xFFFFFF55);
        if (y + 42 < height - 77) {
            if (category == ServerLightingViewPackets.BLOCKS
                    && entry.detail().equals("non_emissive")) {
                graphics.text(font,
                        Component.translatable("screen.totem-lumen.server_preview.non_emissive"),
                        left, y + 29, 0xFFAAAAAA);
            } else if (category == ServerLightingViewPackets.BLOCKS && !entry.flag()) {
                graphics.text(font,
                        Component.translatable("screen.totem-lumen.server_preview.lit_state_only"),
                        left, y + 29, 0xFFAAAAAA);
            } else if (!entry.detail().isEmpty()) {
                graphics.textWithWordWrap(font, Component.literal(entry.detail()), left, y + 29,
                        panelWidth, 0xFFAAAAAA);
            }
        }
    }

    private String formatRgb(float red, float green, float blue) {
        return Math.round(red * 100) + "/" + Math.round(green * 100) + "/" + Math.round(blue * 100);
    }

    private void iconLine(GuiGraphicsExtractor graphics, ItemStack icon, Component label, int x, int y) {
        graphics.item(icon, x, y);
        graphics.text(font, label, x + 19, y + 4, 0xFFFFFFFF);
    }

    private Component onOff(boolean enabled) {
        return Component.translatable(enabled ? "screen.totem-lumen.value.on"
                : "screen.totem-lumen.value.off");
    }

    private Component origin(int value) {
        return Component.translatable(switch (value) {
            case 1 -> "screen.totem-lumen.server_preview.origin.data_pack";
            case 2 -> "screen.totem-lumen.server_preview.origin.config";
            case 3 -> "screen.totem-lumen.server_preview.origin.default_data_pack";
            default -> "screen.totem-lumen.server_preview.origin.default";
        });
    }

    private String blockName(ServerLightingViewPackets.Entry entry) {
        Block block = BuiltInRegistries.BLOCK.getValue(entry.id());
        if (block == null || block.asItem() == Items.AIR) return entry.id().toString();
        return new ItemStack(block.asItem()).getHoverName().getString();
    }

    private ItemStack icon(ServerLightingViewPackets.Entry entry) {
        Item item = BuiltInRegistries.ITEM.getValue(entry.iconItemId());
        return new ItemStack(item == null || item == Items.AIR ? Items.GLOWSTONE_DUST : item);
    }

    void stage(LightingRuleDraft draft) {
        if (!ClientLightingPackManager.canManage(minecraft) || staleDrafts) {
            throw new IllegalArgumentException("Lighting rules changed or no local world is open");
        }
        ServerLightingViewPackets.Summary summary = ClientServerLightingViewState.summary();
        if (summary == null) throw new IllegalArgumentException("Server lighting rules are unavailable");
        if (drafts.isEmpty()) draftRevision = summary.revision();
        if (draftRevision != summary.revision()) {
            staleDrafts = true;
            throw new IllegalArgumentException("Server lighting rules changed; reopen the preview");
        }
        if (draft.changed()) {
            if (drafts.size() >= 8_192 && !drafts.containsKey(draft.id())) {
                throw new IllegalArgumentException("Too many pending lighting rules");
            }
            drafts.put(draft.id(), draft);
        } else {
            drafts.remove(draft.id());
        }
        if (drafts.isEmpty()) draftRevision = -1L;
        closePending = false;
        status = Component.empty();
        updateRows();
    }

    void saved() {
        drafts.clear();
        draftRevision = -1L;
        staleDrafts = false;
        closePending = false;
        status = Component.translatable("screen.totem-lumen.server_preview.saved");
        requestPage();
    }

    void selectPack(String packId) {
        ServerLightingViewPackets.Summary summary = ClientServerLightingViewState.summary();
        if (summary == null || (!packId.isEmpty() && summary.enabledPacks().stream()
                .noneMatch(pack -> pack.id().equals(packId)))) return;
        if (selectedPackId.equals(packId)) return;
        selectedPackId = packId;
        pageNumber = 0;
        scroll = 0;
        selected = null;
        cancelCloseWarning();
        requestPage();
    }

    private Component packLabel() {
        ServerLightingViewPackets.Summary summary = ClientServerLightingViewState.summary();
        if (selectedPackId.isEmpty() || summary == null) {
            return Component.translatable("screen.totem-lumen.server_preview.pack_combined");
        }
        return summary.enabledPacks().stream().filter(pack -> pack.id().equals(selectedPackId))
                .findFirst().<Component>map(pack -> Component.translatable(
                        "screen.totem-lumen.server_preview.pack_selected", pack.title()))
                .orElseGet(() -> Component.translatable("screen.totem-lumen.server_preview.pack_combined"));
    }

    private void cancelCloseWarning() {
        if (closePending) {
            closePending = false;
            status = staleDrafts
                    ? Component.translatable("screen.totem-lumen.server_preview.stale_drafts")
                    : Component.empty();
        }
    }

    @Override
    public void onClose() {
        if (!drafts.isEmpty() && !closePending) {
            closePending = true;
            status = Component.translatable("screen.totem-lumen.server_preview.confirm_discard");
            return;
        }
        minecraft.gui.setScreen(parent);
    }
}
