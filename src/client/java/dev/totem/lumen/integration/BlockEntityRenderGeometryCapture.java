package dev.totem.lumen.integration;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.geometry.BlockModelMeshRegistry;
import dev.totem.lumen.render.RendererSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Captures Model/ModelPart submissions made while Minecraft is submitting one block-entity
 * renderer. Mutable Minecraft model objects never escape this render-thread boundary; only copied
 * block-local vertex positions are retained by Totem Lumen.
 */
public final class BlockEntityRenderGeometryCapture {
    private static final ThreadLocal<Deque<CaptureContext>> CONTEXTS =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static boolean setupFailureLogged;
    private static boolean captureFailureLogged;

    private BlockEntityRenderGeometryCapture() {
    }

    public static void begin(BlockEntityRenderState state, PoseStack poseStack) {
        if (!RendererSettings.rendererEnabled()) return;
        if (state == null || state.blockPos == null || poseStack == null) return;
        var level = Minecraft.getInstance().level;
        if (level == null) return;

        Matrix4f base = new Matrix4f(poseStack.last().pose());
        float determinant = base.determinant();
        if (!Float.isFinite(determinant) || Math.abs(determinant) < 1.0e-8f) return;
        Matrix4f inverse = base.invert(new Matrix4f());

        String typeId = state.blockEntityType == null
                ? "unknown"
                : BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(state.blockEntityType).toString();
        String dimensionId = level.dimension().identifier().toString();
        BlockPos pos = new BlockPos(state.blockPos.getX(), state.blockPos.getY(), state.blockPos.getZ());
        CONTEXTS.get().push(new CaptureContext(dimensionId, pos, typeId, inverse));
    }

    public static void end() {
        Deque<CaptureContext> stack = CONTEXTS.get();
        if (stack.isEmpty()) return;
        CaptureContext context = stack.pop();
        if (stack.isEmpty()) CONTEXTS.remove();
        if (!context.hadModelSubmission) return;

        float[] positions = toFloatArray(context.positions);
        BlockEntityRenderGeometryCache.updateSupplemental(
                context.dimensionId,
                context.pos,
                context.typeId,
                positions
        );
    }

    /** Captures one queued model after applying the exact model render state supplied by Minecraft. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void captureModel(Model<?> model, Object state, PoseStack poseStack) {
        CaptureContext context = current();
        if (context == null || model == null || poseStack == null) return;
        if (!context.markSubmission(model, poseStack)) return;
        context.hadModelSubmission = true;

        try {
            model.resetPose();
            ((Model) model).setupAnim(state);
        } catch (Throwable failure) {
            if (!setupFailureLogged) {
                setupFailureLogged = true;
                TotemLumenClient.LOGGER.warn(
                        "P14D could not apply one block-entity model state before geometry capture; using current model pose",
                        failure
                );
            }
        }
        capturePart(context, model.root(), poseStack);
    }

    /** Captures a renderer-submitted standalone ModelPart using its already-resolved pose. */
    public static void captureModelPart(ModelPart part, PoseStack poseStack) {
        CaptureContext context = current();
        if (context == null || part == null || poseStack == null) return;
        if (!context.markSubmission(part, poseStack)) return;
        context.hadModelSubmission = true;
        capturePart(context, part, poseStack);
    }

    private static void capturePart(CaptureContext context, ModelPart part, PoseStack submitPose) {
        try {
            Matrix4f rendererLocal = new Matrix4f(context.baseInverse).mul(submitPose.last().pose());
            PoseStack partStack = new PoseStack();
            part.visit(partStack, (partPose, path, cubeIndex, cube) -> {
                Matrix4f transform = new Matrix4f(rendererLocal).mul(partPose.pose());
                for (ModelPart.Polygon polygon : cube.polygons) {
                    ModelPart.Vertex[] vertices = polygon.vertices();
                    if (vertices == null || vertices.length != 4) continue;
                    for (ModelPart.Vertex vertex : vertices) {
                        Vector3f point = new Vector3f(
                                vertex.worldX(),
                                vertex.worldY(),
                                vertex.worldZ()
                        );
                        transform.transformPosition(point);
                        context.positions.add(canonicalFloat(point.x));
                        context.positions.add(canonicalFloat(point.y));
                        context.positions.add(canonicalFloat(point.z));
                    }
                }
            });
        } catch (Throwable failure) {
            if (!captureFailureLogged) {
                captureFailureLogged = true;
                TotemLumenClient.LOGGER.warn(
                        "P14D block-entity ModelPart geometry capture failed; vanilla rendering continues unchanged",
                        failure
                );
            }
        }
    }

    private static CaptureContext current() {
        Deque<CaptureContext> stack = CONTEXTS.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    private static float canonicalFloat(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("block-entity model emitted non-finite vertex coordinate");
        }
        if (Math.abs(value) < 0.0000001f) return 0.0f;
        if (Math.abs(value - 1.0f) < 0.0000001f) return 1.0f;
        return value;
    }

    private static float[] toFloatArray(List<Float> values) {
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    private static long submissionHash(Object source, PoseStack poseStack) {
        long hash = 0xcbf29ce484222325L;
        hash ^= System.identityHashCode(source);
        hash *= 0x100000001b3L;
        float[] matrix = new float[16];
        poseStack.last().pose().get(matrix);
        for (float value : matrix) {
            hash ^= Float.floatToRawIntBits(value);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static final class CaptureContext {
        final String dimensionId;
        final BlockPos pos;
        final String typeId;
        final Matrix4f baseInverse;
        final List<Float> positions = new ArrayList<>();
        final Set<Long> submissions = new HashSet<>();
        boolean hadModelSubmission;

        CaptureContext(String dimensionId, BlockPos pos, String typeId, Matrix4f baseInverse) {
            this.dimensionId = dimensionId;
            this.pos = pos;
            this.typeId = typeId;
            this.baseInverse = baseInverse;
        }

        boolean markSubmission(Object source, PoseStack poseStack) {
            return submissions.add(submissionHash(source, poseStack));
        }
    }
}
