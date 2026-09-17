package dev.totem.lumen.geometry;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.totem.lumen.mixin.FluidRendererCaptureMixin;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.Arrays;

/** Build-time ABI gate for the Minecraft 26.2 fluid tessellation capture boundary. */
public final class P14EFluidMixinDescriptorVerifier {
    private static final Class<?>[] TESSELATE_26_2 = {
            BlockAndTintGetter.class,
            BlockPos.class,
            FluidRenderer.Output.class,
            BlockState.class,
            FluidState.class
    };

    private P14EFluidMixinDescriptorVerifier() {
    }

    public static void main(String[] args) throws Exception {
        Method tesselate = FluidRenderer.class.getDeclaredMethod("tesselate", TESSELATE_26_2);
        if (tesselate.getReturnType() != void.class) {
            throw new IllegalStateException("FluidRenderer.tesselate must return void");
        }
        verifyTesselateCallback("totemLumen$beginFluidCapture");
        verifyTesselateCallback("totemLumen$endFluidCapture");

        Class<?>[] addFace = addFaceDescriptor();
        Method face = FluidRenderer.class.getDeclaredMethod("addFace", addFace);
        if (face.getReturnType() != void.class) {
            throw new IllegalStateException("FluidRenderer.addFace must return void");
        }
        verifyAddFaceCallback(addFace);

        Method getBuilder = FluidRenderer.Output.class.getDeclaredMethod("getBuilder", ChunkSectionLayer.class);
        if (getBuilder.getReturnType() != VertexConsumer.class) {
            throw new IllegalStateException("FluidRenderer.Output.getBuilder must return VertexConsumer");
        }

        System.out.println(
                "P14E fluid mixin descriptor verification PASS: minecraft=26.2, "
                        + "tesselate=BlockAndTintGetter+BlockPos+Output+BlockState+FluidState, "
                        + "addFace=VertexConsumer+20F+II+Z, output=ChunkSectionLayer->VertexConsumer"
        );
    }

    private static void verifyTesselateCallback(String methodName) {
        Method handler = Arrays.stream(FluidRendererCaptureMixin.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing P14E callback " + methodName));
        Class<?>[] callback = handler.getParameterTypes();
        if (callback.length != TESSELATE_26_2.length + 1) {
            throw new IllegalStateException(methodName + " callback parameter count mismatch");
        }
        for (int i = 0; i < TESSELATE_26_2.length; i++) {
            if (callback[i] != TESSELATE_26_2[i]) {
                throw new IllegalStateException(
                        methodName + " callback parameter " + i + " mismatch: expected "
                                + TESSELATE_26_2[i].getName() + " but found " + callback[i].getName()
                );
            }
        }
        if (callback[callback.length - 1] != CallbackInfo.class) {
            throw new IllegalStateException(methodName + " callback must end with CallbackInfo");
        }
    }

    private static void verifyAddFaceCallback(Class<?>[] targetDescriptor) {
        Method handler = Arrays.stream(FluidRendererCaptureMixin.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("totemLumen$captureFluidFace"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing P14E addFace callback"));
        Class<?>[] callback = handler.getParameterTypes();
        if (callback.length != targetDescriptor.length + 1) {
            throw new IllegalStateException(
                    "P14E addFace callback parameter count mismatch: " + callback.length
            );
        }
        for (int i = 0; i < targetDescriptor.length; i++) {
            if (callback[i] != targetDescriptor[i]) {
                throw new IllegalStateException(
                        "P14E addFace callback parameter " + i + " mismatch: expected "
                                + targetDescriptor[i].getName() + " but found " + callback[i].getName()
                );
            }
        }
        if (callback[callback.length - 1] != CallbackInfo.class) {
            throw new IllegalStateException("P14E addFace callback must end with CallbackInfo");
        }
    }

    private static Class<?>[] addFaceDescriptor() {
        Class<?>[] result = new Class<?>[24];
        result[0] = VertexConsumer.class;
        for (int i = 1; i <= 20; i++) result[i] = float.class;
        result[21] = int.class;
        result[22] = int.class;
        result[23] = boolean.class;
        return result;
    }
}
