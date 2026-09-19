package dev.totem.lumen.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Build-time guard for the Minecraft 26.2 submit-node ABI used by P14D capture mixins.
 *
 * <p>This exists because a stale Mixin callback descriptor still compiles normally and otherwise
 * fails only when Minecraft transforms SubmitNodeStorage during client startup.</p>
 */
public final class P14DSubmitMixinVerifier {
    private static final String SUBMIT_MODEL_SELECTOR =
            "submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/rendertype/RenderType;"
                    + "IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;"
                    + "ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V";

    private static final String SUBMIT_MODEL_PART_SELECTOR =
            "submitModelPart(Lnet/minecraft/client/model/geom/ModelPart;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/rendertype/RenderType;"
                    + "IILnet/minecraft/client/renderer/texture/TextureAtlasSprite;"
                    + "ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;I)V";

    private P14DSubmitMixinVerifier() {
    }

    public static void main(String[] args) throws Exception {
        Class<?>[] submitModel = {
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
        requireDeclared(SubmitNodeStorage.class, "submitModel", submitModel);
        requireDeclared(SubmitNodeCollection.class, "submitModel", submitModel);

        Class<?>[] submitModelPart = {
                ModelPart.class,
                PoseStack.class,
                RenderType.class,
                int.class,
                int.class,
                TextureAtlasSprite.class,
                int.class,
                ModelFeatureRenderer.CrumblingOverlay.class,
                int.class
        };
        // In 26.2 the model-part overloads are default methods on OrderedSubmitNodeCollector.
        requirePublic(OrderedSubmitNodeCollector.class, "submitModelPart", submitModelPart);

        Class<?>[] modelCallback = append(submitModel, CallbackInfo.class);
        verifySelector(
                SubmitNodeStorageBlockEntityMixin.class,
                "totemLumen$captureModel",
                SUBMIT_MODEL_SELECTOR,
                modelCallback
        );
        verifySelector(
                SubmitNodeCollectionBlockEntityMixin.class,
                "totemLumen$captureModel",
                SUBMIT_MODEL_SELECTOR,
                modelCallback
        );

        Class<?>[] modelPartCallback = append(submitModelPart, CallbackInfo.class);
        verifySelector(
                SubmitNodeStorageBlockEntityMixin.class,
                "totemLumen$captureModelPart",
                SUBMIT_MODEL_PART_SELECTOR,
                modelPartCallback
        );
        verifySelector(
                SubmitNodeCollectionBlockEntityMixin.class,
                "totemLumen$captureModelPart",
                SUBMIT_MODEL_PART_SELECTOR,
                modelPartCallback
        );

        System.out.println(
                "P14D submit ABI verification PASS: "
                        + "submitModel=sprite+outline, submitModelPart=26.2-no-sheeted/no-foil"
        );
    }

    private static void requireDeclared(
            Class<?> owner,
            String name,
            Class<?>[] parameterTypes
    ) throws NoSuchMethodException {
        owner.getDeclaredMethod(name, parameterTypes);
    }

    private static void requirePublic(
            Class<?> owner,
            String name,
            Class<?>[] parameterTypes
    ) throws NoSuchMethodException {
        owner.getMethod(name, parameterTypes);
    }

    private static void verifySelector(
            Class<?> mixinClass,
            String callbackName,
            String expectedSelector,
            Class<?>[] callbackTypes
    ) throws NoSuchMethodException {
        Method callback = mixinClass.getDeclaredMethod(callbackName, callbackTypes);
        Inject inject = callback.getAnnotation(Inject.class);
        if (inject == null) {
            throw new IllegalStateException("Missing @Inject on " + mixinClass.getSimpleName()
                    + "." + callbackName);
        }
        if (!Arrays.asList(inject.method()).contains(expectedSelector)) {
            throw new IllegalStateException(
                    "Unexpected selector on " + mixinClass.getSimpleName() + "." + callbackName
                            + ": " + Arrays.toString(inject.method())
            );
        }
    }

    private static Class<?>[] append(Class<?>[] source, Class<?> tail) {
        Class<?>[] result = Arrays.copyOf(source, source.length + 1);
        result[source.length] = tail;
        return result;
    }
}
