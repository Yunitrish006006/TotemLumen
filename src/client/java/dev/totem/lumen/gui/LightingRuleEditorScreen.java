package dev.totem.lumen.gui;

import dev.totem.lumen.network.ServerLightingViewPackets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/** Edits one block locally; the preview screen saves all staged edits in one data pack. */
public final class LightingRuleEditorScreen extends Screen {
    private final ServerLightingPreviewScreen parent;
    private final ServerLightingViewPackets.Entry entry;
    private int red;
    private int green;
    private int blue;
    private int strength;
    private Button apply;
    private Component status = Component.empty();
    private int left;
    private int panelWidth;

    public LightingRuleEditorScreen(ServerLightingPreviewScreen parent, ServerLightingViewPackets.Entry entry,
                                    LightingRuleDraft existing) {
        super(Component.translatable("screen.totem-lumen.rule_editor.title"));
        this.parent = parent;
        this.entry = entry;
        red = existing == null ? Math.round(entry.red() * 255) : existing.red();
        green = existing == null ? Math.round(entry.green() * 255) : existing.green();
        blue = existing == null ? Math.round(entry.blue() * 255) : existing.blue();
        strength = existing == null ? entry.strength() : existing.strength();
    }

    @Override
    protected void init() {
        panelWidth = Math.min(320, Math.max(190, width - 24));
        left = (width - panelWidth) / 2;
        addRenderableWidget(slider(57, 0, 255, red, "red", next -> red = next));
        addRenderableWidget(slider(81, 0, 255, green, "green", next -> green = next));
        addRenderableWidget(slider(105, 0, 255, blue, "blue", next -> blue = next));
        addRenderableWidget(slider(129, -1, 15, strength, "strength", next -> strength = next));
        apply = addRenderableWidget(Button.builder(
                Component.translatable("screen.totem-lumen.rule_editor.stage"), ignored -> stage())
                .bounds(left, height - 53, panelWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),
                ignored -> onClose()).bounds(left, height - 27, panelWidth, 20).build());
        apply.active = ClientLightingPackManager.canManage(minecraft) && entry.origin() != 2;
        if (entry.origin() == 2) {
            status = Component.translatable("screen.totem-lumen.rule_editor.config_override");
        }
    }

    private RuleSlider slider(int y, int min, int max, int initial, String channel, IntConsumer setter) {
        IntFunction<Component> label = value -> Component.translatable(
                "screen.totem-lumen.rule_editor." + channel,
                value == -1 ? Component.translatable("screen.totem-lumen.server_preview.by_state")
                        : Component.literal(Integer.toString(value)));
        return new RuleSlider(left, y, panelWidth, min, max, initial, label, setter);
    }

    private void stage() {
        try {
            parent.stage(new LightingRuleDraft(entry, red, green, blue, strength));
            minecraft.gui.setScreen(parent);
        } catch (IllegalArgumentException problem) {
            status = Component.literal(problem.getMessage());
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
        graphics.text(font, Component.literal(entry.id().toString()), left, 37, 0xFFCCCCCC);
        graphics.text(font, Component.translatable("screen.totem-lumen.rule_editor.overlay_hint"),
                left, 155, 0xFFAAAAAA);
        if (!status.getString().isEmpty()) {
            graphics.textWithWordWrap(font, status, left, Math.max(170, height - 78),
                    panelWidth, 0xFFFFAA66);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    private static final class RuleSlider extends AbstractSliderButton {
        private final int min;
        private final int max;
        private final IntFunction<Component> label;
        private final IntConsumer setter;

        RuleSlider(int x, int y, int width, int min, int max, int initial,
                   IntFunction<Component> label, IntConsumer setter) {
            super(x, y, width, 20, Component.empty(), (initial - min) / (double) (max - min));
            this.min = min;
            this.max = max;
            this.label = label;
            this.setter = setter;
            updateMessage();
        }

        private int current() {
            return min + (int) Math.round(value * (max - min));
        }

        @Override protected void updateMessage() { setMessage(label.apply(current())); }
        @Override protected void applyValue() { setter.accept(current()); }
    }
}
