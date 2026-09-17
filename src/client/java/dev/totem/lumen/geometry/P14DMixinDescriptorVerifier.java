package dev.totem.lumen.geometry;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.totem.lumen.mixin.SubmitNodeCollectionBlockEntityMixin;
import dev.totem.lumen.mixin.SubmitNodeStorageBlockEntityMixin;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Build-time regression gate for P14D's Minecraft-facing model submission mixins.
 *
 * <p>These signatures are intentionally checked against the actual Minecraft 26.2 classes on the
 * client runtime classpath. A source-compatible mixin can still fail at game startup when its
 * callback descriptor no longer matches the transformed target, so this verifier keeps that ABI
 * mismatch inside CI instead of deferring it to an in-game crash.</p>
 */
public final class P14DMixinDescriptorVerifier {
    private static final Class<?>[] SUBMIT_MODEL_26_2 = {
            Model.class,
            Object.class,
            PoseStack.class,
            RenderType.class,
            int.class,
            int.class,
            int.class,
            TextureAtlasSprite.class,
            int.class,
            ModelFeatureRenderer.CrumblingOverlay.class
    };

    private P14DMixinDescriptorVerifier() {
    }

    public static void main(String[] args) throws Exception {
        verifySubmitModel(
                SubmitNodeStorage.class,
                SubmitNodeStorageBlockEntityMixin.class
        );
        verifySubmitModel(
                SubmitNodeCollection.class,
                SubmitNodeCollectionBlockEntityMixin.class
        );
        System.out.println(
                "P14D mixin descriptor verification PASS: minecraft=26.2, "
                        + "submitModel=Model+Object+PoseStack+RenderType+III+TextureAtlasSprite+I+CrumblingOverlay"
        );
    }

    private static void verifySubmitModel(Class<?> targetClass, Class<?> mixinClass) throws Exception {
        Method target = targetClass.getDeclaredMethod("submitModel", SUBMIT_MODEL_26_2);
        if (target.getReturnType() != void.class) {
            throw new IllegalStateException(targetClass.getName() + ".submitModel must return void");
        }

        Method handler = Arrays.stream(mixinClass.getDeclaredMethods())
                .filter(method -> method.getName().equals("totemLumen$captureModelWithSprite"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing P14D submitModel callback in " + mixinClass.getName()
                ));

        Class<?>[] callback = handler.getParameterTypes();
        if (callback.length != SUBMIT_MODEL_26_2.length + 1) {
            throw new IllegalStateException(
                    mixinClass.getName() + " callback parameter count mismatch: " + callback.length
            );
        }
        for (int i = 0; i < SUBMIT_MODEL_26_2.length; i++) {
            if (callback[i] != SUBMIT_MODEL_26_2[i]) {
                throw new IllegalStateException(
                        mixinClass.getName() + " callback parameter " + i + " mismatch: expected "
                                + SUBMIT_MODEL_26_2[i].getName() + " but found " + callback[i].getName()
                );
            }
        }
        if (callback[callback.length - 1] != CallbackInfo.class) {
            throw new IllegalStateException(
                    mixinClass.getName() + " callback must end with CallbackInfo"
            );
        }

        boolean hasLegacyCallback = Arrays.stream(mixinClass.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("totemLumen$captureModel"));
        if (hasLegacyCallback) {
            throw new IllegalStateException(
                    mixinClass.getName() + " still contains the incompatible legacy submitModel callback"
            );
        }
    }
}
