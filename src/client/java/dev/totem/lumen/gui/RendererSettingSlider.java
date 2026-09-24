package dev.totem.lumen.gui;

import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/** Discrete slider used for renderer settings with more than one selectable value. */
final class RendererSettingSlider extends AbstractSliderButton {
    private final int maximumIndex;
    private final IntSupplier currentIndex;
    private final IntConsumer applyIndex;
    private final Function<Integer, Component> label;
    private final Runnable afterRelease;

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
        this(x, y, width, height, maximumIndex, currentIndex, applyIndex, label, () -> {
        });
    }

    RendererSettingSlider(
            int x,
            int y,
            int width,
            int height,
            int maximumIndex,
            IntSupplier currentIndex,
            IntConsumer applyIndex,
            Function<Integer, Component> label,
            Runnable afterRelease
    ) {
        super(x, y, width, height, label.apply(currentIndex.getAsInt()), normalized(currentIndex.getAsInt(), maximumIndex));
        this.maximumIndex = maximumIndex;
        this.currentIndex = currentIndex;
        this.applyIndex = applyIndex;
        this.label = label;
        this.afterRelease = afterRelease;
    }

    @Override
    protected void updateMessage() {
        setMessage(label.apply(selectedIndex()));
    }

    @Override
    protected void applyValue() {
        int selected = selectedIndex();
        applyIndex.accept(selected);
        value = normalized(currentIndex.getAsInt(), maximumIndex);
        updateMessage();
    }

    @Override
    public void onRelease(net.minecraft.client.input.MouseButtonEvent event) {
        super.onRelease(event);
        afterRelease.run();
    }

    private int selectedIndex() {
        return Math.max(0, Math.min(maximumIndex, (int) Math.round(value * maximumIndex)));
    }

    private static double normalized(int index, int maximumIndex) {
        return maximumIndex == 0 ? 0.0 : (double) index / maximumIndex;
    }
}
