package dev.totem.lumen.integration;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.geometry.BlockModelMeshRegistry;
import dev.totem.lumen.material.BaselineSurfaceProperties;
import dev.totem.lumen.material.SurfaceProperties;
import dev.totem.lumen.scene.BlockGeometryCode;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * P14C generic geometry extraction for Minecraft/Fabric block-state models.
 *
 * <p>The renderer API is the source of truth for static/chunk block geometry. This intentionally
 * asks {@link FabricBlockStateModel#emitQuads} with culling disabled instead of inferring shape from
 * block ids. Model vertices are copied immediately into Totem Lumen-owned arrays and no Minecraft
 * model/quad object is retained by the ray scene.</p>
 */
public final class MinecraftBlockModelMeshResolver {
    private static final float CUBE_EPSILON = 0.0002f;

    private static Object observedModelSet;
    private static boolean extractionFailureLogged;

    private MinecraftBlockModelMeshResolver() {
    }

    /** Called once per extraction frame so resource-pack model reloads refresh resident sections. */
    public static void checkModelSetReload() {
        try {
            observeModelSet(Minecraft.getInstance().getModelManager().getBlockStateModelSet());
        } catch (Throwable ignored) {
            // Model access can be unavailable during early/late client lifecycle. Actual extraction
            // retains its own logged fallback and will observe the model set once it becomes valid.
        }
    }

    public static int geometryCode(BlockState state, ClientLevel level, BlockPos pos) {
        if (state.isAir()) {
            return BlockGeometryCode.FULL_CUBE;
        }

        String sourceId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        SurfaceProperties surface = BaselineSurfaceProperties.forBlock(sourceId);

        // P15 keeps its dedicated optical ABI until generic transmissive interfaces are added.
        if (isTransmissiveGlassPane(sourceId)) {
            return BlockGeometryCode.transmissivePane(connectionMask(state), transmissionTint(sourceId));
        }
        if (isTransmissiveGlassCube(sourceId)) {
            return BlockGeometryCode.glassCube(transmissionTint(sourceId));
        }

        // Fluids are meshed by Minecraft's fluid renderer rather than BlockStateModel. Preserve the
        // established conservative volume until the dedicated fluid-surface geometry domain lands.
        if (!state.getFluidState().isEmpty() && state.getRenderShape() == RenderShape.INVISIBLE) {
            return BlockGeometryCode.surfaceCube(surface.roughness(), surface.metallic());
        }

        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return BlockGeometryCode.modelMesh(0);
        }

        boolean hadStaticGeometry = false;
        boolean registryCapacityFallback = false;

        ModelMeshData modelMesh = emitModelQuads(state, level, pos);
        if (modelMesh.positions().length > 0) {
            hadStaticGeometry = true;
            // A leaf-like model can still be a canonical six-face cube geometrically. It must stay
            // on MODEL_MESH when any sprite contains transparent texels so rays can pass through
            // the texture silhouette instead of hitting an opaque SURFACE_CUBE.
            if (isCanonicalUnitCube(modelMesh.positions()) && !modelMesh.hasCutout()) {
                return BlockGeometryCode.surfaceCube(surface.roughness(), surface.metallic());
            }
            int meshId = BlockModelMeshRegistry.register(
                    modelMesh.positions(),
                    modelMesh.uvs(),
                    modelMesh.alphaMaskWords()
            );
            if (meshId >= 0) {
                return BlockGeometryCode.modelMesh(meshId);
            }
            registryCapacityFallback = true;
        }

        // Models rendered by special/block-entity paths may not emit chunk quads. Their outline
        // shape is a conservative geometry fallback until that dynamic render domain is captured.
        float[] shapeQuads = outlineShapeQuads(state.getShape(level, pos));
        if (shapeQuads.length > 0) {
            hadStaticGeometry = true;
            if (isCanonicalUnitCube(shapeQuads)) {
                return BlockGeometryCode.surfaceCube(surface.roughness(), surface.metallic());
            }
            int shapeId = BlockModelMeshRegistry.register(shapeQuads);
            if (shapeId >= 0) {
                return BlockGeometryCode.modelMesh(shapeId);
            }
            registryCapacityFallback = true;
        }

        // Capacity exhaustion must never make visible geometry disappear. A solid voxel is less
        // accurate than the emitted mesh, but remains conservative for lighting and traversal.
        if (registryCapacityFallback || hadStaticGeometry) {
            return BlockGeometryCode.surfaceCube(surface.roughness(), surface.metallic());
        }

        // Empty model + empty outline should not become a fake solid cube.
        return BlockGeometryCode.modelMesh(0);
    }

    private static ModelMeshData emitModelQuads(BlockState state, ClientLevel level, BlockPos pos) {
        try {
            var modelSet = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
            observeModelSet(modelSet);
            BlockStateModel model = modelSet.get(state);
            FabricBlockStateModel fabricModel = (FabricBlockStateModel) model;

            List<Float> positions = new ArrayList<>();
            List<Float> uvs = new ArrayList<>();
            List<Integer> alphaMaskWords = new ArrayList<>();
            Renderer renderer = Renderer.get();
            QuadEmitter emitter = renderer.quadEmitter(
                    quad -> appendQuad(positions, uvs, alphaMaskWords, quad)
            );
            RandomSource random = RandomSource.create(state.getSeed(pos));
            fabricModel.emitQuads(emitter, level, pos, state, random, direction -> false);
            return new ModelMeshData(
                    toFloatArray(positions),
                    toFloatArray(uvs),
                    toIntArray(alphaMaskWords)
            );
        } catch (Throwable failure) {
            if (!extractionFailureLogged) {
                extractionFailureLogged = true;
                TotemLumenClient.LOGGER.warn(
                        "P14C block-model quad extraction failed; affected blocks use outline-shape fallback",
                        failure
                );
            }
            return ModelMeshData.EMPTY;
        }
    }

    private static synchronized void observeModelSet(Object modelSet) {
        if (observedModelSet == modelSet) {
            return;
        }
        boolean reload = observedModelSet != null;
        observedModelSet = modelSet;
        if (reload) {
            // Keep old mesh ids alive while sections are progressively rebuilt. New model geometry
            // receives new/deduplicated ids, so resource reload never invalidates resident voxels.
            SceneExtractionBridge.refreshModelGeometry();
            TotemLumenClient.LOGGER.info(
                    "P14C detected a new BlockStateModelSet; queued populated sections for model-geometry refresh"
            );
        }
    }

    private static void appendQuad(
            List<Float> positions,
            List<Float> uvs,
            List<Integer> alphaMaskWords,
            MutableQuadView quad
    ) {
        Vector3f scratch = new Vector3f();
        for (int vertex = 0; vertex < 4; vertex++) {
            quad.copyPos(vertex, scratch);
            positions.add(canonicalFloat(scratch.x));
            positions.add(canonicalFloat(scratch.y));
            positions.add(canonicalFloat(scratch.z));
        }

        P14AlphaCutoutMask.Capture capture = P14AlphaCutoutMask.capture(quad);
        for (float uv : capture.uvs()) uvs.add(canonicalFloat(uv));
        for (int word : capture.maskWords()) alphaMaskWords.add(word);
    }

    private static float canonicalFloat(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Block model emitted non-finite vertex coordinate");
        }
        if (Math.abs(value) < 0.0000001f) return 0.0f;
        if (Math.abs(value - 1.0f) < 0.0000001f) return 1.0f;
        return value;
    }

    private static float[] toFloatArray(List<Float> values) {
        float[] result = new float[values.size()];
        for (int index = 0; index < values.size(); index++) {
            result[index] = values.get(index);
        }
        return result;
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int index = 0; index < values.size(); index++) {
            result[index] = values.get(index);
        }
        return result;
    }

    private static boolean isCanonicalUnitCube(float[] quads) {
        if (quads.length != 6 * BlockModelMeshRegistry.FLOATS_PER_QUAD) {
            return false;
        }

        int faceMask = 0;
        for (int quad = 0; quad < 6; quad++) {
            int base = quad * BlockModelMeshRegistry.FLOATS_PER_QUAD;
            int constantAxis = -1;
            int side = -1;
            for (int axis = 0; axis < 3; axis++) {
                float first = quads[base + axis];
                boolean constant = true;
                for (int vertex = 1; vertex < 4; vertex++) {
                    if (Math.abs(quads[base + vertex * 3 + axis] - first) > CUBE_EPSILON) {
                        constant = false;
                        break;
                    }
                }
                if (constant && nearBoundary(first)) {
                    constantAxis = axis;
                    side = first > 0.5f ? 1 : 0;
                    break;
                }
            }
            if (constantAxis < 0) return false;

            int cornerMask = 0;
            int axisA = (constantAxis + 1) % 3;
            int axisB = (constantAxis + 2) % 3;
            for (int vertex = 0; vertex < 4; vertex++) {
                float a = quads[base + vertex * 3 + axisA];
                float b = quads[base + vertex * 3 + axisB];
                if (!nearBoundary(a) || !nearBoundary(b)) return false;
                int corner = (a > 0.5f ? 1 : 0) | (b > 0.5f ? 2 : 0);
                cornerMask |= 1 << corner;
            }
            if (cornerMask != 0xF) return false;

            int faceBit = 1 << (constantAxis * 2 + side);
            if ((faceMask & faceBit) != 0) return false;
            faceMask |= faceBit;
        }
        return faceMask == 0x3F;
    }

    private static boolean nearBoundary(float value) {
        return Math.abs(value) <= CUBE_EPSILON || Math.abs(value - 1.0f) <= CUBE_EPSILON;
    }

    private static float[] outlineShapeQuads(VoxelShape shape) {
        List<AABB> boxes = shape.toAabbs();
        if (boxes.isEmpty()) {
            return new float[0];
        }
        List<Float> values = new ArrayList<>(boxes.size() * 6 * BlockModelMeshRegistry.FLOATS_PER_QUAD);
        for (AABB box : boxes) {
            appendBox(values, box);
        }
        return toFloatArray(values);
    }

    private static void appendBox(List<Float> out, AABB b) {
        // -X, +X, -Y, +Y, -Z, +Z. Winding is irrelevant because P14C triangles are two-sided.
        quad(out, b.minX, b.minY, b.minZ, b.minX, b.minY, b.maxZ, b.minX, b.maxY, b.maxZ, b.minX, b.maxY, b.minZ);
        quad(out, b.maxX, b.minY, b.maxZ, b.maxX, b.minY, b.minZ, b.maxX, b.maxY, b.minZ, b.maxX, b.maxY, b.maxZ);
        quad(out, b.minX, b.minY, b.maxZ, b.minX, b.minY, b.minZ, b.maxX, b.minY, b.minZ, b.maxX, b.minY, b.maxZ);
        quad(out, b.minX, b.maxY, b.minZ, b.minX, b.maxY, b.maxZ, b.maxX, b.maxY, b.maxZ, b.maxX, b.maxY, b.minZ);
        quad(out, b.maxX, b.minY, b.minZ, b.minX, b.minY, b.minZ, b.minX, b.maxY, b.minZ, b.maxX, b.maxY, b.minZ);
        quad(out, b.minX, b.minY, b.maxZ, b.maxX, b.minY, b.maxZ, b.maxX, b.maxY, b.maxZ, b.minX, b.maxY, b.maxZ);
    }

    private static void quad(List<Float> out, double... xyz) {
        for (double value : xyz) out.add((float) value);
    }

    private record ModelMeshData(
            float[] positions,
            float[] uvs,
            int[] alphaMaskWords
    ) {
        private static final ModelMeshData EMPTY =
                new ModelMeshData(new float[0], new float[0], new int[0]);

        boolean hasCutout() {
            for (int word : alphaMaskWords) {
                if (word != -1) return true;
            }
            return false;
        }
    }

    private static boolean isTransmissiveGlassPane(String sourceId) {
        return sourceId.equals("minecraft:glass_pane") || sourceId.endsWith("_stained_glass_pane");
    }

    private static boolean isTransmissiveGlassCube(String sourceId) {
        if (sourceId.equals("minecraft:tinted_glass")) return false;
        return sourceId.equals("minecraft:glass") || sourceId.endsWith("_stained_glass");
    }

    private static int connectionMask(BlockState state) {
        int mask = 0;
        if (booleanPropertyValue(state, "north")) mask |= BlockGeometryCode.CONNECT_NORTH;
        if (booleanPropertyValue(state, "east")) mask |= BlockGeometryCode.CONNECT_EAST;
        if (booleanPropertyValue(state, "south")) mask |= BlockGeometryCode.CONNECT_SOUTH;
        if (booleanPropertyValue(state, "west")) mask |= BlockGeometryCode.CONNECT_WEST;
        return mask;
    }

    private static boolean booleanPropertyValue(BlockState state, String name) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) {
                return "true".equals(propertyValue(state, property));
            }
        }
        return false;
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static int transmissionTint(String sourceId) {
        if (sourceId.startsWith("minecraft:white_")) return BlockGeometryCode.TINT_WHITE;
        if (sourceId.startsWith("minecraft:orange_")) return BlockGeometryCode.TINT_ORANGE;
        if (sourceId.startsWith("minecraft:magenta_")) return BlockGeometryCode.TINT_MAGENTA;
        if (sourceId.startsWith("minecraft:light_blue_")) return BlockGeometryCode.TINT_LIGHT_BLUE;
        if (sourceId.startsWith("minecraft:yellow_")) return BlockGeometryCode.TINT_YELLOW;
        if (sourceId.startsWith("minecraft:lime_")) return BlockGeometryCode.TINT_LIME;
        if (sourceId.startsWith("minecraft:pink_")) return BlockGeometryCode.TINT_PINK;
        if (sourceId.startsWith("minecraft:light_gray_")) return BlockGeometryCode.TINT_LIGHT_GRAY;
        if (sourceId.startsWith("minecraft:gray_")) return BlockGeometryCode.TINT_GRAY;
        if (sourceId.startsWith("minecraft:cyan_")) return BlockGeometryCode.TINT_CYAN;
        if (sourceId.startsWith("minecraft:purple_")) return BlockGeometryCode.TINT_PURPLE;
        if (sourceId.startsWith("minecraft:blue_")) return BlockGeometryCode.TINT_BLUE;
        if (sourceId.startsWith("minecraft:brown_")) return BlockGeometryCode.TINT_BROWN;
        if (sourceId.startsWith("minecraft:green_")) return BlockGeometryCode.TINT_GREEN;
        if (sourceId.startsWith("minecraft:red_")) return BlockGeometryCode.TINT_RED;
        if (sourceId.startsWith("minecraft:black_")) return BlockGeometryCode.TINT_BLACK;
        return BlockGeometryCode.TINT_CLEAR;
    }
}
