package dev.totem.lumen.gui;

import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/** Discrete slider used for renderer settings with more than one selectable value. */
class RendererSettingSlider extends AbstractSliderButton {
    private final int maximumIndex;
    private final IntSupplier currentIndex;
    private final IntConsumer applyIndex;
    private final Function<Integer, Component> label;

    RendererSettingSlider(
            int x,
            int y,
            int width,
            int height,
            int maximumIndex,
            IntSupplier currentIndex,
            IntConsumer applyIndex,
            Function<Integer, Component> label
    ) {
        super(x, y, width, height, label.apply(currentIndex.getAsInt()), normalized(currentIndex.getAsInt(), maximumIndex));
        this.maximumIndex = maximumIndex;
        this.currentIndex = currentIndex;
        this.applyIndex = applyIndex;
        this.label = label;
    }

    @Override
    protected void updateMessage() {
        setMessage(label.apply(selectedIndex()));
    }

    @Override
    protected void applyValue() {
        int selected = selectedIndex();
        applyIndex.accept(selected);
        refreshValue();
    }

    protected final void refreshValue() {
        value = normalized(currentIndex.getAsInt(), maximumIndex);
        updateMessage();
    }

    private int selectedIndex() {
        return Math.max(0, Math.min(maximumIndex, (int) Math.round(value * maximumIndex)));
    }

    private static double normalized(int index, int maximumIndex) {
        return maximumIndex == 0 ? 0.0 : (double) index / maximumIndex;
    }
}
