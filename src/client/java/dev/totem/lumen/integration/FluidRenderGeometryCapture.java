package dev.totem.lumen.integration;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.scene.FluidGeometrySnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.List;

/** Thread-local capture scope around one Minecraft FluidRenderer.tesselate call. */
public final class FluidRenderGeometryCapture {
    private static final ThreadLocal<Capture> ACTIVE = new ThreadLocal<>();
    private static volatile boolean firstCaptureLogged;

    private FluidRenderGeometryCapture() {
    }

    public static void begin(BlockPos pos, BlockState blockState, FluidState fluidState) {
        String dimensionId = FluidRenderGeometryCache.activeDimensionId();
        if (dimensionId == null || pos == null || blockState == null || fluidState == null || fluidState.isEmpty()) {
            ACTIVE.remove();
            return;
        }
        String fluidTypeId = BuiltInRegistries.FLUID.getKey(fluidState.getType()).toString();
        boolean fluidOnlyCell = blockState.getBlock() instanceof LiquidBlock;
        ACTIVE.set(new Capture(dimensionId, pos.immutable(), fluidTypeId, fluidOnlyCell));
    }

    public static void face(
            float x0, float y0, float z0, float u0, float v0,
            float x1, float y1, float z1, float u1, float v1,
            float x2, float y2, float z2, float u2, float v2,
            float x3, float y3, float z3, float u3, float v3,
            int color,
            boolean addBackFace
    ) {
        Capture capture = ACTIVE.get();
        if (capture == null) return;
        capture.faces.add(new Face(
                new float[]{x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3},
                new float[]{u0, v0, u1, v1, u2, v2, u3, v3},
                color,
                addBackFace
        ));
    }

    public static void end() {
        Capture capture = ACTIVE.get();
        ACTIVE.remove();
        if (capture == null || capture.faces.isEmpty()) return;

        int quadCount = capture.faces.size();
        float[] positions = new float[quadCount * 12];
        float[] uvs = new float[quadCount * 8];
        int[] colors = new int[quadCount];
        boolean[] doubleSided = new boolean[quadCount];
        for (int i = 0; i < quadCount; i++) {
            Face face = capture.faces.get(i);
            System.arraycopy(face.positions, 0, positions, i * 12, 12);
            System.arraycopy(face.uvs, 0, uvs, i * 8, 8);
            colors[i] = face.color;
            doubleSided[i] = face.doubleSided;
        }

        FluidGeometrySnapshot snapshot = new FluidGeometrySnapshot(
                capture.dimensionId,
                capture.fluidTypeId,
                capture.pos.getX(),
                capture.pos.getY(),
                capture.pos.getZ(),
                capture.fluidOnlyCell,
                positions,
                uvs,
                colors,
                doubleSided
        );
        FluidRenderGeometryCache.store(snapshot);

        if (!firstCaptureLogged) {
            firstCaptureLogged = true;
            TotemLumenClient.LOGGER.info(
                    "P14E exact fluid geometry capture active: fluid={}, block=({}, {}, {}), fluidOnly={}, quads={}",
                    capture.fluidTypeId,
                    capture.pos.getX(),
                    capture.pos.getY(),
                    capture.pos.getZ(),
                    capture.fluidOnlyCell,
                    quadCount
            );
        }
    }

    public static void abort() {
        ACTIVE.remove();
    }

    private static final class Capture {
        private final String dimensionId;
        private final BlockPos pos;
        private final String fluidTypeId;
        private final boolean fluidOnlyCell;
        private final List<Face> faces = new ArrayList<>(6);

        private Capture(String dimensionId, BlockPos pos, String fluidTypeId, boolean fluidOnlyCell) {
            this.dimensionId = dimensionId;
            this.pos = pos;
            this.fluidTypeId = fluidTypeId;
            this.fluidOnlyCell = fluidOnlyCell;
        }
    }

    private record Face(float[] positions, float[] uvs, int color, boolean doubleSided) {
    }
}
