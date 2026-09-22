package dev.totem.lumen.mixin;

import net.minecraft.client.Options;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the native Video Settings reconstruction inputs without reflection. */
@Mixin(OptionsSubScreen.class)
public interface OptionsSubScreenAccessor {
    @Accessor("lastScreen")
    Screen totemLumen$getLastScreen();

    @Accessor("options")
    Options totemLumen$getOptions();

    @Accessor("list")
    OptionsList totemLumen$getList();
}
