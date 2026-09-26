package dev.totem.lumen.geometry;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.totem.lumen.mixin.EntityRenderDispatcherMixin;
import dev.totem.lumen.mixin.EntityRendererStateMixin;
import dev.totem.lumen.mixin.GpuDeviceAccessor;
import dev.totem.lumen.mixin.LevelRendererTakeoverMixin;
import dev.totem.lumen.mixin.SubmitNodeCollectionBlockEntityMixin;
import dev.totem.lumen.mixin.SubmitNodeCollectionItemMixin;
import dev.totem.lumen.mixin.VanillaRgbOverlayMixin;
import dev.totem.lumen.mixin.VideoSettingsRenderProfileMixin;
import dev.totem.lumen.mixin.SubmitNodeStorageBlockEntityMixin;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.entity.Entity;
import org.joml.Vector4f;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * Build-time regression gate for Minecraft-facing render-submission mixins used by P14D/P17.
 *
 * <p>These signatures are intentionally checked against the actual Minecraft 26.3 classes on the
 * client runtime classpath. A source-compatible mixin can still fail at game startup when its
 * callback descriptor no longer matches the transformed target, so this verifier keeps that ABI
 * mismatch inside CI instead of deferring it to an in-game crash.</p>
 */
public final class P14DMixinDescriptorVerifier {
    private static final Class<?>[] SUBMIT_MODEL_26_3 = {
            Model.class,
            Object.class,
            PoseStack.class,
            RenderType.class,
            int.class,
            int.class,
            int.class,
            UvMapping.class,
            int.class
    };

    private static final Class<?>[] ENTITY_SUBMIT_26_3 = {
            EntityRenderState.class,
            CameraRenderState.class,
            double.class,
            double.class,
            double.class,
            PoseStack.class,
            SubmitNodeCollector.class
    };

    private static final Class<?>[] LEVEL_RENDER_26_3 = {
            GraphicsResourceAllocator.class,
            boolean.class,
            CameraRenderState.class,
            GpuBufferSlice.class,
            Vector4f.class,
            boolean.class,
            boolean.class
    };

    private static final Class<?>[] SUBMIT_ITEM_26_3 = {
            PoseStack.class,
            ItemDisplayContext.class,
            int.class,
            int.class,
            int.class,
            int[].class,
            ItemQuads.class,
            ItemStackRenderState.FoilType.class
    };

    private P14DMixinDescriptorVerifier() {
    }

    public static void main(String[] args) throws Exception {
        verifyGpuDeviceBackendAccessor();
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
        verifyItemSubmit();
        verifyLevelRenderTakeover();
        verifyHeldLightRenderHook();
        verifyVideoSettingsProfileHooks();
        System.out.println(
                "P14D/P17 mixin descriptor verification PASS: minecraft=26.3, "
                        + "gpuBackend=FrontendGpuDevice.backend, "
                        + "submitModel=Model+Object+PoseStack+RenderType+III+UvMapping+I, "
                        + "createRenderState=Entity+F->EntityRenderState, "
                        + "entitySubmit=EntityRenderState+CameraRenderState+DDD+PoseStack+SubmitNodeCollector, "
                        + "itemSubmit=PoseStack+ItemDisplayContext+III+int[]+ItemQuads+FoilType, "
                        + "levelRender=GraphicsResourceAllocator+Z+CameraRenderState+GpuBufferSlice+Vector4f+ZZ, "
                        + "videoSettings=qualityOptions/displayOptions/preferenceOptions/addOptions"
        );
    }

    private static void verifyGpuDeviceBackendAccessor() throws Exception {
        ClassNode accessorClass = new ClassNode();
        try (var bytecode = GpuDeviceAccessor.class.getResourceAsStream("GpuDeviceAccessor.class")) {
            if (bytecode == null) {
                throw new IllegalStateException("GPU backend accessor class file is missing");
            }
            new ClassReader(bytecode).accept(accessorClass, ClassReader.SKIP_CODE);
        }
        boolean targetsFrontend = accessorClass.invisibleAnnotations != null
                && accessorClass.invisibleAnnotations.stream()
                .filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))
                .map(annotation -> annotation.values)
                .filter(values -> values != null)
                .anyMatch(values -> values.stream()
                        .anyMatch(value -> value instanceof List<?> targets
                                && targets.contains(Type.getType(FrontendGpuDevice.class))));
        if (!targetsFrontend) {
            throw new IllegalStateException("GPU backend accessor must target FrontendGpuDevice");
        }
        Field backend = FrontendGpuDevice.class.getDeclaredField("backend");
        Method accessor = GpuDeviceAccessor.class.getDeclaredMethod("totemLumen$getBackend");
        Accessor annotation = accessor.getAnnotation(Accessor.class);
        if (backend.getType() != GpuDeviceBackend.class
                || accessor.getReturnType() != GpuDeviceBackend.class
                || annotation == null
                || !annotation.value().equals("backend")) {
            throw new IllegalStateException("FrontendGpuDevice backend accessor descriptor mismatch");
        }
    }

    private static void verifySubmitModel(Class<?> targetClass, Class<?> mixinClass) throws Exception {
        Method target = targetClass.getDeclaredMethod("submitModel", SUBMIT_MODEL_26_3);
        if (target.getReturnType() != void.class) {
            throw new IllegalStateException(targetClass.getName() + ".submitModel must return void");
        }

        Method handler = Arrays.stream(mixinClass.getDeclaredMethods())
                .filter(method -> method.getName().equals("totemLumen$captureModelWithUvMapping"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing P14D/P17 submitModel callback in " + mixinClass.getName()
                ));

        Class<?>[] callback = handler.getParameterTypes();
        if (callback.length != SUBMIT_MODEL_26_3.length + 1) {
            throw new IllegalStateException(
                    mixinClass.getName() + " callback parameter count mismatch: " + callback.length
            );
        }
        for (int i = 0; i < SUBMIT_MODEL_26_3.length; i++) {
            if (callback[i] != SUBMIT_MODEL_26_3[i]) {
                throw new IllegalStateException(
                        mixinClass.getName() + " callback parameter " + i + " mismatch: expected "
                                + SUBMIT_MODEL_26_3[i].getName() + " but found " + callback[i].getName()
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
                "createRenderState",
                Entity.class,
                float.class
        );
        if (target.getReturnType() != EntityRenderState.class) {
            throw new IllegalStateException(
                    "EntityRenderer.createRenderState must return EntityRenderState"
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
        Method target = EntityRenderDispatcher.class.getDeclaredMethod("submit", ENTITY_SUBMIT_26_3);
        if (target.getReturnType() != void.class) {
            throw new IllegalStateException("EntityRenderDispatcher.submit must return void");
        }

        verifyEntityCallback("totemLumen$beginEntityCapture");
        verifyEntityCallback("totemLumen$endEntityCapture");
    }

    private static void verifyItemSubmit() throws Exception {
        Method target = SubmitNodeCollection.class.getDeclaredMethod("submitItem", SUBMIT_ITEM_26_3);
        if (target.getReturnType() != void.class) {
            throw new IllegalStateException("SubmitNodeCollection.submitItem must return void");
        }

        Method handler = Arrays.stream(SubmitNodeCollectionItemMixin.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("totemLumen$captureItem"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing P17 item-renderer submit callback"
                ));
        Class<?>[] callback = handler.getParameterTypes();
        if (callback.length != SUBMIT_ITEM_26_3.length + 1) {
            throw new IllegalStateException(
                    "P17 item callback parameter count mismatch: " + callback.length
            );
        }
        for (int i = 0; i < SUBMIT_ITEM_26_3.length; i++) {
            if (callback[i] != SUBMIT_ITEM_26_3[i]) {
                throw new IllegalStateException(
                        "P17 item callback parameter " + i + " mismatch: expected "
                                + SUBMIT_ITEM_26_3[i].getName() + " but found " + callback[i].getName()
                );
            }
        }
        if (callback[callback.length - 1] != CallbackInfo.class) {
            throw new IllegalStateException("P17 item callback must end with CallbackInfo");
        }

        if (callback[6] != ItemQuads.class) {
            throw new IllegalStateException("P17 item callback must receive ItemQuads");
        }
    }

    private static void verifyLevelRenderTakeover() throws Exception {
        Method target = LevelRenderer.class.getDeclaredMethod("render", LEVEL_RENDER_26_3);
        if (target.getReturnType() != void.class) {
            throw new IllegalStateException("LevelRenderer.render must return void");
        }

        Method handler = Arrays.stream(LevelRendererTakeoverMixin.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("totemLumen$replaceVanillaWorldDrawing"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing LevelRenderer world-takeover callback"
                ));

        Class<?>[] callback = handler.getParameterTypes();
        if (callback.length != LEVEL_RENDER_26_3.length + 1) {
            throw new IllegalStateException(
                    "LevelRenderer takeover callback parameter count mismatch: "
                            + callback.length
            );
        }
        for (int i = 0; i < LEVEL_RENDER_26_3.length; i++) {
            if (callback[i] != LEVEL_RENDER_26_3[i]) {
                throw new IllegalStateException(
                        "LevelRenderer takeover callback parameter " + i + " mismatch: expected "
                                + LEVEL_RENDER_26_3[i].getName() + " but found "
                                + callback[i].getName()
                );
            }
        }
        if (callback[callback.length - 1] != CallbackInfo.class) {
            throw new IllegalStateException(
                    "LevelRenderer takeover callback must end with CallbackInfo"
            );
        }
    }

    private static void verifyHeldLightRenderHook() {
        Method handler = Arrays.stream(VanillaRgbOverlayMixin.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("totemLumen$drawHeldLight"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing held-light LevelRenderer callback"));
        Class<?>[] callback = handler.getParameterTypes();
        if (callback.length != LEVEL_RENDER_26_3.length + 1) {
            throw new IllegalStateException("Held-light callback parameter count mismatch");
        }
        for (int i = 0; i < LEVEL_RENDER_26_3.length; i++) {
            if (callback[i] != LEVEL_RENDER_26_3[i]) {
                throw new IllegalStateException("Held-light callback parameter " + i + " mismatch");
            }
        }
        if (callback[callback.length - 1] != CallbackInfo.class) {
            throw new IllegalStateException("Held-light callback must end with CallbackInfo");
        }
    }

    private static void verifyVideoSettingsProfileHooks() throws Exception {
        for (String methodName : new String[] {
                "qualityOptions",
                "displayOptions",
                "preferenceOptions"
        }) {
            Method target = VideoSettingsScreen.class.getDeclaredMethod(methodName, Options.class);
            if (!target.getReturnType().isArray()
                    || target.getReturnType().getComponentType() != OptionInstance.class) {
                throw new IllegalStateException(
                        "VideoSettingsScreen." + methodName + " must return OptionInstance[]"
                );
            }
        }

        Method addOptions = VideoSettingsScreen.class.getDeclaredMethod("addOptions");
        if (addOptions.getReturnType() != void.class) {
            throw new IllegalStateException("VideoSettingsScreen.addOptions must return void");
        }

        Method qualityHandler = Arrays.stream(VideoSettingsRenderProfileMixin.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("totemLumen$filterQualityOptions"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing Video Settings quality-profile filter callback"
                ));
        Class<?>[] qualityCallback = qualityHandler.getParameterTypes();
        if (qualityCallback.length != 2
                || qualityCallback[0] != Options.class
                || qualityCallback[1] != CallbackInfoReturnable.class) {
            throw new IllegalStateException(
                    "Video Settings quality-profile callback descriptor mismatch: "
                            + Arrays.toString(qualityCallback)
            );
        }

        Method addHandler = Arrays.stream(VideoSettingsRenderProfileMixin.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("totemLumen$addQualitySectionControls"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing Video Settings quality-section injection callback"
                ));
        Class<?>[] addCallback = addHandler.getParameterTypes();
        if (addCallback.length != 1 || addCallback[0] != CallbackInfo.class) {
            throw new IllegalStateException(
                    "Video Settings quality-section callback descriptor mismatch: "
                            + Arrays.toString(addCallback)
            );
        }
    }

    private static void verifyEntityCallback(String methodName) {
        Method handler = Arrays.stream(EntityRenderDispatcherMixin.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing P17 entity submit callback " + methodName
                ));

        Class<?>[] callback = handler.getParameterTypes();
        if (callback.length != ENTITY_SUBMIT_26_3.length + 1) {
            throw new IllegalStateException(
                    methodName + " callback parameter count mismatch: " + callback.length
            );
        }
        for (int i = 0; i < ENTITY_SUBMIT_26_3.length; i++) {
            if (callback[i] != ENTITY_SUBMIT_26_3[i]) {
                throw new IllegalStateException(
                        methodName + " callback parameter " + i + " mismatch: expected "
                                + ENTITY_SUBMIT_26_3[i].getName() + " but found " + callback[i].getName()
                );
            }
        }
        if (callback[callback.length - 1] != CallbackInfo.class) {
            throw new IllegalStateException(methodName + " callback must end with CallbackInfo");
        }
    }
}
