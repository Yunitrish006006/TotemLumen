package dev.totem.lumen.integration;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.scene.DynamicEntitySnapshot;
import dev.totem.lumen.render.RendererSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.registries.BuiltInRegistries;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Captures renderer-resolved Model and item submissions while Minecraft submits one entity.
 *
 * <p>Alpha 42 starts with Player/general LivingEntity renderers because they share the
 * Model/ModelPart submit path. The Alpha 59 follow-up also captures item-renderer quads through
 * the generic {@code submitItem} command and extends the same model path to explicit vehicle and
 * projectile families. Text/display and custom-geometry command families remain separate adapters.
 * Mutable Minecraft model/render-state objects never enter the retained Totem Lumen scene.</p>
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
        if (!RendererSettings.rendererEnabled() || !RendererSettings.entityRayTracingEnabled()) return;
        if (!supportsModelFamily(state)) return;
        if (poseStack == null) return;
        var level = Minecraft.getInstance().level;
        if (level == null) return;

        Matrix4f base = new Matrix4f(poseStack.last().pose());
        float determinant = base.determinant();
        if (!Float.isFinite(determinant) || Math.abs(determinant) < 1.0e-8f) return;

        String typeId = entityTypeId(state);
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
        if ((!context.hadModelSubmission && !context.hadItemSubmission)
                || context.positions.isEmpty()) return;

        DynamicEntitySnapshot snapshot = new DynamicEntitySnapshot(
                context.instanceId,
                context.dimensionId,
                context.entityTypeId,
                context.worldX,
                context.worldY,
                context.worldZ,
                context.positions.toArray(),
                context.uvs.toArray()
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

    /** Captures renderer-resolved item quads after Minecraft has applied the item transform. */
    public static void captureItem(PoseStack poseStack, List<BakedQuad> quads) {
        CaptureContext context = current();
        if (context == null || poseStack == null || quads == null || quads.isEmpty()) return;
        if (!context.markSubmission(quads, poseStack)) return;
        context.hadItemSubmission = true;

        try {
            Matrix4f dispatcherLocal = new Matrix4f(context.baseInverse).mul(poseStack.last().pose());
            for (BakedQuad quad : quads) {
                if (quad == null) continue;
                for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
                    Vector3f point = new Vector3f(quad.position(vertex));
                    dispatcherLocal.transformPosition(point);
                    point.sub(
                            (float) context.renderX,
                            (float) context.renderY,
                            (float) context.renderZ
                    );
                    context.positions.add(canonicalFloat(point.x));
                    context.positions.add(canonicalFloat(point.y));
                    context.positions.add(canonicalFloat(point.z));

                    long packedUv = quad.packedUV(vertex);
                    context.uvs.add(canonicalFloat(Float.intBitsToFloat((int) packedUv)));
                    context.uvs.add(canonicalFloat(Float.intBitsToFloat((int) (packedUv >>> 32))));
                }
            }
        } catch (Throwable failure) {
            if (!captureFailureLogged) {
                captureFailureLogged = true;
                TotemLumenClient.LOGGER.warn(
                        "P17 item-renderer quad capture failed; vanilla rendering continues unchanged",
                        failure
                );
            }
        }
    }

    private static String entityTypeId(EntityRenderState state) {
        return state.entityType == null
                ? "unknown"
                : BuiltInRegistries.ENTITY_TYPE.getKey(state.entityType).toString();
    }

    private static boolean supportsModelFamily(EntityRenderState state) {
        if (state instanceof LivingEntityRenderState) return true;
        String typeId = entityTypeId(state);
        if ("minecraft:item".equals(typeId)) return true;
        if (typeId.endsWith("_minecart")
                || "minecraft:minecart".equals(typeId)
                || "minecraft:boat".equals(typeId)
                || "minecraft:chest_boat".equals(typeId)) return true;
        return switch (typeId) {
            case "minecraft:arrow",
                    "minecraft:spectral_arrow",
                    "minecraft:trident",
                    "minecraft:firework_rocket",
                    "minecraft:wind_charge",
                    "minecraft:small_fireball",
                    "minecraft:fireball",
                    "minecraft:dragon_fireball",
                    "minecraft:llama_spit",
                    "minecraft:shulker_bullet",
                    "minecraft:wither_skull",
                    "minecraft:egg",
                    "minecraft:snowball",
                    "minecraft:ender_pearl",
                    "minecraft:experience_bottle",
                    "minecraft:potion",
                    "minecraft:evoker_fangs",
                    "minecraft:fishing_bobber" -> true;
            default -> false;
        };
    }

    private static void capturePart(CaptureContext context, ModelPart part, PoseStack submitPose) {
        try {
            Matrix4f dispatcherLocal = new Matrix4f(context.baseInverse).mul(submitPose.last().pose());
            PoseStack partStack = new PoseStack();
            part.visit(partStack, (partPose, path, cubeIndex, cube) -> {
                Matrix4f transform = new Matrix4f(dispatcherLocal).mul(partPose.pose());
                Vector3f point = new Vector3f();
                for (ModelPart.Polygon polygon : cube.polygons) {
                    ModelPart.Vertex[] vertices = polygon.vertices();
                    if (vertices == null || vertices.length != 4) continue;
                    for (ModelPart.Vertex vertex : vertices) {
                        point.set(vertex.worldX(), vertex.worldY(), vertex.worldZ());
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
                        context.uvs.add(canonicalFloat(vertex.u()));
                        context.uvs.add(canonicalFloat(vertex.v()));
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

    private static long submissionHash(Object source, PoseStack poseStack) {
        long hash = 0xcbf29ce484222325L;
        hash ^= System.identityHashCode(source);
        hash *= 0x100000001b3L;
        Matrix4f matrix = poseStack.last().pose();
        hash = hashFloat(hash, matrix.m00());
        hash = hashFloat(hash, matrix.m01());
        hash = hashFloat(hash, matrix.m02());
        hash = hashFloat(hash, matrix.m03());
        hash = hashFloat(hash, matrix.m10());
        hash = hashFloat(hash, matrix.m11());
        hash = hashFloat(hash, matrix.m12());
        hash = hashFloat(hash, matrix.m13());
        hash = hashFloat(hash, matrix.m20());
        hash = hashFloat(hash, matrix.m21());
        hash = hashFloat(hash, matrix.m22());
        hash = hashFloat(hash, matrix.m23());
        hash = hashFloat(hash, matrix.m30());
        hash = hashFloat(hash, matrix.m31());
        hash = hashFloat(hash, matrix.m32());
        return hashFloat(hash, matrix.m33());
    }

    private static long hashFloat(long hash, float value) {
        hash ^= Float.floatToRawIntBits(value);
        return hash * 0x100000001b3L;
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
        final FloatAccumulator positions = new FloatAccumulator(512);
        final FloatAccumulator uvs = new FloatAccumulator(384);
        final Set<Long> submissions = new HashSet<>();
        boolean hadModelSubmission;
        boolean hadItemSubmission;

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

    private static final class FloatAccumulator {
        private float[] values;
        private int size;

        FloatAccumulator(int initialCapacity) {
            values = new float[Math.max(16, initialCapacity)];
        }

        void add(float value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, values.length << 1);
            }
            values[size++] = value;
        }

        boolean isEmpty() {
            return size == 0;
        }

        float[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
