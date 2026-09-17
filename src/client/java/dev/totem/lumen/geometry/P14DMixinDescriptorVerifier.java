package dev.totem.lumen.geometry;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.totem.lumen.mixin.EntityRenderDispatcherMixin;
import dev.totem.lumen.mixin.EntityRendererStateMixin;
import dev.totem.lumen.mixin.SubmitNodeCollectionBlockEntityMixin;
import dev.totem.lumen.mixin.SubmitNodeStorageBlockEntityMixin;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Build-time regression gate for Minecraft-facing render-submission mixins used by P14D/P17.
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

    private static final Class<?>[] ENTITY_SUBMIT_26_2 = {
            EntityRenderState.class,
            CameraRenderState.class,
            double.class,
            double.class,
            double.class,
            PoseStack.class,
            SubmitNodeCollector.class
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
        verifyEntityStateBinding();
        verifyEntitySubmit();
        System.out.println(
                "P14D/P17 mixin descriptor verification PASS: minecraft=26.2, "
                        + "submitModel=Model+Object+PoseStack+RenderType+III+TextureAtlasSprite+I+CrumblingOverlay, "
                        + "entityState=Entity+F->EntityRenderState, "
                        + "entitySubmit=EntityRenderState+CameraRenderState+DDD+PoseStack+SubmitNodeCollector"
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
                        "Missing P14D/P17 submitModel callback in " + mixinClass.getName()
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

    private static void verifyEntityStateBinding() throws Exception {
        Method target = EntityRenderer.class.getDeclaredMethod(
                "getAndUpdateRenderState",
                Entity.class,
                float.class
        );
        if (target.getReturnType() != EntityRenderState.class) {
            throw new IllegalStateException(
                    "EntityRenderer.getAndUpdateRenderState must return EntityRenderState"
            );
        }

        Method handler = Arrays.stream(EntityRendererStateMixin.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("totemLumen$bindEntityRenderState"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing P17 entity render-state binding callback"
                ));
        Class<?>[] callback = handler.getParameterTypes();
        if (callback.length != 3
                || callback[0] != Entity.class
                || callback[1] != float.class
                || callback[2] != CallbackInfoReturnable.class) {
            throw new IllegalStateException(
                    "P17 entity render-state binding callback descriptor mismatch: "
                            + Arrays.toString(callback)
            );
        }
    }

    private static void verifyEntitySubmit() throws Exception {
        Method target = EntityRenderDispatcher.class.getDeclaredMethod("submit", ENTITY_SUBMIT_26_2);
        if (target.getReturnType() != void.class) {
            throw new IllegalStateException("EntityRenderDispatcher.submit must return void");
        }

        verifyEntityCallback("totemLumen$beginEntityCapture");
        verifyEntityCallback("totemLumen$endEntityCapture");
    }

    private static void verifyEntityCallback(String methodName) {
        Method handler = Arrays.stream(EntityRenderDispatcherMixin.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing P17 entity submit callback " + methodName
                ));

        Class<?>[] callback = handler.getParameterTypes();
        if (callback.length != ENTITY_SUBMIT_26_2.length + 1) {
            throw new IllegalStateException(
                    methodName + " callback parameter count mismatch: " + callback.length
            );
        }
        for (int i = 0; i < ENTITY_SUBMIT_26_2.length; i++) {
            if (callback[i] != ENTITY_SUBMIT_26_2[i]) {
                throw new IllegalStateException(
                        methodName + " callback parameter " + i + " mismatch: expected "
                                + ENTITY_SUBMIT_26_2[i].getName() + " but found " + callback[i].getName()
                );
            }
        }
        if (callback[callback.length - 1] != CallbackInfo.class) {
            throw new IllegalStateException(methodName + " callback must end with CallbackInfo");
        }
    }
}
