package dev.totem.lumen.mixin;

import dev.totem.lumen.gui.TotemLumenVideoSettingsIntegration;
import dev.totem.lumen.render.RendererSettings;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;
import java.util.Set;

/**
 * Removes vanilla world-render-only options from Video Settings while the Totem render profile is
 * active. Shared display/interface and scene-availability options remain untouched.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsRenderProfileMixin {
    @Inject(
            method = "addOptions",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/OptionsList;addHeader(Lnet/minecraft/network/chat/Component;)V",
                    ordinal = 1,
                    shift = At.Shift.AFTER
            )
    )
    private void totemLumen$addQualitySectionControls(CallbackInfo ci) {
        OptionsSubScreenAccessor accessor = (OptionsSubScreenAccessor) (Object) this;
        TotemLumenVideoSettingsIntegration.addQualityControls(
                (VideoSettingsScreen) (Object) this,
                accessor.totemLumen$getList()
        );
    }

    @Inject(method = "qualityOptions", at = @At("RETURN"), cancellable = true)
    private static void totemLumen$filterQualityOptions(
            Options options,
            CallbackInfoReturnable<OptionInstance<?>[]> cir
    ) {
        cir.setReturnValue(filterForActiveProfile(options, cir.getReturnValue()));
    }

    @Inject(method = "displayOptions", at = @At("RETURN"), cancellable = true)
    private static void totemLumen$filterDisplayOptions(
            Options options,
            CallbackInfoReturnable<OptionInstance<?>[]> cir
    ) {
        cir.setReturnValue(filterForActiveProfile(options, cir.getReturnValue()));
    }

    @Inject(method = "preferenceOptions", at = @At("RETURN"), cancellable = true)
    private static void totemLumen$filterPreferenceOptions(
            Options options,
            CallbackInfoReturnable<OptionInstance<?>[]> cir
    ) {
        cir.setReturnValue(filterForActiveProfile(options, cir.getReturnValue()));
    }

    private static OptionInstance<?>[] filterForActiveProfile(
            Options options,
            OptionInstance<?>[] original
    ) {
        if (!RendererSettings.rendererEnabled()) {
            return original;
        }

        Set<OptionInstance<?>> replacedByTotem = Set.of(
                options.graphicsPreset(),
                options.ambientOcclusion(),
                options.entityShadows(),
                options.cloudStatus(),
                options.cloudRange(),
                options.weatherRadius(),
                options.cutoutLeaves(),
                options.improvedTransparency(),
                options.chunkSectionFadeInTime(),
                options.textureFiltering()
        );

        return Arrays.stream(original)
                .filter(option -> !replacedByTotem.contains(option))
                .toArray(OptionInstance<?>[]::new);
    }
}
