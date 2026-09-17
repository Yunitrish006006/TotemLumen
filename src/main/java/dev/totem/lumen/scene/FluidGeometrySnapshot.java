package dev.totem.lumen.scene;

import java.util.Arrays;
import java.util.Objects;

/** Immutable, Minecraft-object-free snapshot of one resolved fluid cell's rendered quads. */
public final class FluidGeometrySnapshot {
    private final String dimensionId;
    private final String fluidTypeId;
    private final int blockX;
    private final int blockY;
    private final int blockZ;
    private final int sectionX;
    private final int sectionY;
    private final int sectionZ;
    private final boolean fluidOnlyCell;
    private final float[] quadPositions;
    private final float[] quadUvs;
    private final int[] quadColors;
    private final boolean[] doubleSided;

    public FluidGeometrySnapshot(
            String dimensionId,
            String fluidTypeId,
            int blockX,
            int blockY,
            int blockZ,
            boolean fluidOnlyCell,
            float[] quadPositions,
            float[] quadUvs,
            int[] quadColors,
            boolean[] doubleSided
    ) {
        this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        this.fluidTypeId = Objects.requireNonNull(fluidTypeId, "fluidTypeId");
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
        this.sectionX = Math.floorDiv(blockX, 16);
        this.sectionY = Math.floorDiv(blockY, 16);
        this.sectionZ = Math.floorDiv(blockZ, 16);
        this.fluidOnlyCell = fluidOnlyCell;

        Objects.requireNonNull(quadPositions, "quadPositions");
        Objects.requireNonNull(quadUvs, "quadUvs");
        Objects.requireNonNull(quadColors, "quadColors");
        Objects.requireNonNull(doubleSided, "doubleSided");
        if (quadPositions.length == 0 || quadPositions.length % 12 != 0) {
            throw new IllegalArgumentException("quadPositions must contain one or more 4-vertex quads");
        }
        int quadCount = quadPositions.length / 12;
        if (quadUvs.length != quadCount * 8) {
            throw new IllegalArgumentException("quadUvs must contain 8 floats per quad");
        }
        if (quadColors.length != quadCount) {
            throw new IllegalArgumentException("quadColors must contain one ARGB value per quad");
        }
        if (doubleSided.length != quadCount) {
            throw new IllegalArgumentException("doubleSided must contain one flag per quad");
        }
        for (float value : quadPositions) requireFinite(value, "quad position");
        for (float value : quadUvs) requireFinite(value, "quad uv");

        this.quadPositions = quadPositions.clone();
        this.quadUvs = quadUvs.clone();
        this.quadColors = quadColors.clone();
        this.doubleSided = doubleSided.clone();
    }

    public String dimensionId() {
        return dimensionId;
    }

    public String fluidTypeId() {
        return fluidTypeId;
    }

    public int blockX() {
        return blockX;
    }

    public int blockY() {
        return blockY;
    }

    public int blockZ() {
        return blockZ;
    }

    public int sectionX() {
        return sectionX;
    }

    public int sectionY() {
        return sectionY;
    }

    public int sectionZ() {
        return sectionZ;
    }

    /** True for a LiquidBlock cell; false when fluid coexists with waterlogged/custom block geometry. */
    public boolean fluidOnlyCell() {
        return fluidOnlyCell;
    }

    public int quadCount() {
        return quadColors.length;
    }

    public float[] copyQuadPositions() {
        return quadPositions.clone();
    }

    public float[] copyQuadUvs() {
        return quadUvs.clone();
    }

    public int[] copyQuadColors() {
        return quadColors.clone();
    }

    public boolean[] copyDoubleSided() {
        return doubleSided.clone();
    }

    /** World X for one stored section-local vertex without collapsing the section origin to float. */
    public double worldVertexX(int quad, int vertex) {
        return sectionX * 16.0 + quadPositions[positionIndex(quad, vertex)];
    }

    public double worldVertexY(int quad, int vertex) {
        return sectionY * 16.0 + quadPositions[positionIndex(quad, vertex) + 1];
    }

    public double worldVertexZ(int quad, int vertex) {
        return sectionZ * 16.0 + quadPositions[positionIndex(quad, vertex) + 2];
    }

    public boolean geometryEquals(FluidGeometrySnapshot other) {
        return other != null
                && dimensionId.equals(other.dimensionId)
                && fluidTypeId.equals(other.fluidTypeId)
                && blockX == other.blockX
                && blockY == other.blockY
                && blockZ == other.blockZ
                && fluidOnlyCell == other.fluidOnlyCell
                && Arrays.equals(quadPositions, other.quadPositions)
                && Arrays.equals(quadUvs, other.quadUvs)
                && Arrays.equals(quadColors, other.quadColors)
                && Arrays.equals(doubleSided, other.doubleSided);
    }

    private int positionIndex(int quad, int vertex) {
        if (quad < 0 || quad >= quadCount()) throw new IndexOutOfBoundsException("quad=" + quad);
        if (vertex < 0 || vertex >= 4) throw new IndexOutOfBoundsException("vertex=" + vertex);
        return quad * 12 + vertex * 3;
    }

    private static void requireFinite(float value, String label) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException(label + " must be finite");
    }
}
