package dev.totem.lumen.integration;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.scene.DynamicEntitySnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
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
 * Captures renderer-resolved Model submissions while Minecraft submits one living entity.
 *
 * <p>Alpha 42 deliberately starts with Player/general LivingEntity renderers because they share the
 * Model/ModelPart submit path. Items, boats, projectiles and custom-geometry command families are
 * separate follow-up adapters. Mutable Minecraft model/render-state objects never enter the retained
 * Totem Lumen scene.</p>
 */
public final class EntityRenderGeometryCapture {
    private static final ThreadLocal<Deque<CaptureContext>> CONTEXTS =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static boolean setupFailureLogged;
    private static boolean captureFailureLogged;

    private EntityRenderGeometryCapture() {
    }

    public static void begin(
            EntityRenderState state,
            PoseStack poseStack,
            double renderX,
            double renderY,
            double renderZ
    ) {
        if (!(state instanceof LivingEntityRenderState) || poseStack == null) return;
        var level = Minecraft.getInstance().level;
        if (level == null) return;

        Matrix4f base = new Matrix4f(poseStack.last().pose());
        float determinant = base.determinant();
        if (!Float.isFinite(determinant) || Math.abs(determinant) < 1.0e-8f) return;

        String typeId = state.entityType == null
                ? "unknown"
                : BuiltInRegistries.ENTITY_TYPE.getKey(state.entityType).toString();
        String dimensionId = level.dimension().identifier().toString();
        long instanceId = EntityRenderGeometryCache.instanceId(state);
        CONTEXTS.get().push(new CaptureContext(
                state,
                instanceId,
                dimensionId,
                typeId,
                state.x,
                state.y,
                state.z,
                level.getGameTime(),
                renderX,
                renderY,
                renderZ,
                base.invert(new Matrix4f())
        ));
    }

    public static void end() {
        Deque<CaptureContext> stack = CONTEXTS.get();
        if (stack.isEmpty()) return;
        CaptureContext context = stack.pop();
        if (stack.isEmpty()) CONTEXTS.remove();
        if (!context.hadModelSubmission || context.positions.isEmpty()) return;

        DynamicEntitySnapshot snapshot = new DynamicEntitySnapshot(
                context.instanceId,
                context.dimensionId,
                context.entityTypeId,
                context.worldX,
                context.worldY,
                context.worldZ,
                toFloatArray(context.positions)
        );
        EntityRenderGeometryCache.publish(context.renderState, snapshot, context.levelGameTime);
    }

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
                        "P17 could not apply one living-entity model state before geometry capture; using current model pose",
                        failure
                );
            }
        }
        capturePart(context, model.root(), poseStack);
    }

    private static void capturePart(CaptureContext context, ModelPart part, PoseStack submitPose) {
        try {
            Matrix4f dispatcherLocal = new Matrix4f(context.baseInverse).mul(submitPose.last().pose());
            PoseStack partStack = new PoseStack();
            part.visit(partStack, (partPose, path, cubeIndex, cube) -> {
                Matrix4f transform = new Matrix4f(dispatcherLocal).mul(partPose.pose());
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
                        // EntityRenderDispatcher translates by the camera-relative render position
                        // before renderer/model transforms. Remove only that placement so the
                        // retained geometry is entity-local while preserving pose/rotation/scale.
                        point.sub(
                                (float) context.renderX,
                                (float) context.renderY,
                                (float) context.renderZ
                        );
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
                        "P17 living-entity ModelPart geometry capture failed; vanilla rendering continues unchanged",
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
            throw new IllegalArgumentException("entity model emitted non-finite vertex coordinate");
        }
        if (Math.abs(value) < 0.0000001f) return 0.0f;
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
        final EntityRenderState renderState;
        final long instanceId;
        final String dimensionId;
        final String entityTypeId;
        final double worldX;
        final double worldY;
        final double worldZ;
        final long levelGameTime;
        final double renderX;
        final double renderY;
        final double renderZ;
        final Matrix4f baseInverse;
        final List<Float> positions = new ArrayList<>();
        final Set<Long> submissions = new HashSet<>();
        boolean hadModelSubmission;

        CaptureContext(
                EntityRenderState renderState,
                long instanceId,
                String dimensionId,
                String entityTypeId,
                double worldX,
                double worldY,
                double worldZ,
                long levelGameTime,
                double renderX,
                double renderY,
                double renderZ,
                Matrix4f baseInverse
        ) {
            this.renderState = renderState;
            this.instanceId = instanceId;
            this.dimensionId = dimensionId;
            this.entityTypeId = entityTypeId;
            this.worldX = worldX;
            this.worldY = worldY;
            this.worldZ = worldZ;
            this.levelGameTime = levelGameTime;
            this.renderX = renderX;
            this.renderY = renderY;
            this.renderZ = renderZ;
            this.baseInverse = baseInverse;
        }

        boolean markSubmission(Object source, PoseStack poseStack) {
            return submissions.add(submissionHash(source, poseStack));
        }
    }
}
