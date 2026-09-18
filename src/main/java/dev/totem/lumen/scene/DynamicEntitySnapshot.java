package dev.totem.lumen.scene;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable, Minecraft-object-free geometry snapshot for one rendered dynamic entity instance.
 *
 * <p>Captured vertex positions are entity-local. World-space bounds are derived once when the
 * snapshot is created so later CPU/GPU broad-phase code does not need access to a Minecraft
 * {@code Entity} or render state. Absolute world coordinates stay double precision on the CPU;
 * the future GPU ABI can encode section-relative values without losing far-world precision.</p>
 */
public final class DynamicEntitySnapshot {
    private final long instanceId;
    private final String dimensionId;
    private final String entityTypeId;
    private final double worldX;
    private final double worldY;
    private final double worldZ;
    private final double minX;
    private final double minY;
    private final double minZ;
    private final double maxX;
    private final double maxY;
    private final double maxZ;
    private final float[] quadPositions;
    private final float[] quadUvs;

    public DynamicEntitySnapshot(
            long instanceId,
            String dimensionId,
            String entityTypeId,
            double worldX,
            double worldY,
            double worldZ,
            float[] quadPositions
    ) {
        this(
                instanceId,
                dimensionId,
                entityTypeId,
                worldX,
                worldY,
                worldZ,
                quadPositions,
                new float[quadPositions == null ? 0 : quadPositions.length / 3 * 2]
        );
    }

    public DynamicEntitySnapshot(
            long instanceId,
            String dimensionId,
            String entityTypeId,
            double worldX,
            double worldY,
            double worldZ,
            float[] quadPositions,
            float[] quadUvs
    ) {
        if (instanceId <= 0L) {
            throw new IllegalArgumentException("instanceId must be positive");
        }
        this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        this.entityTypeId = Objects.requireNonNull(entityTypeId, "entityTypeId");
        requireFinite(worldX, "worldX");
        requireFinite(worldY, "worldY");
        requireFinite(worldZ, "worldZ");
        Objects.requireNonNull(quadPositions, "quadPositions");
        Objects.requireNonNull(quadUvs, "quadUvs");
        if (quadPositions.length == 0 || quadPositions.length % 12 != 0) {
            throw new IllegalArgumentException("quadPositions must contain one or more 4-vertex quads");
        }
        if (quadUvs.length != quadPositions.length / 3 * 2) {
            throw new IllegalArgumentException("quadUvs must contain one UV pair per entity vertex");
        }

        this.instanceId = instanceId;
        this.worldX = worldX;
        this.worldY = worldY;
        this.worldZ = worldZ;
        this.quadPositions = quadPositions.clone();
        this.quadUvs = quadUvs.clone();
        for (int i = 0; i < this.quadUvs.length; i++) {
            requireFinite(this.quadUvs[i], "quad uv");
        }

        float localMinX = Float.POSITIVE_INFINITY;
        float localMinY = Float.POSITIVE_INFINITY;
        float localMinZ = Float.POSITIVE_INFINITY;
        float localMaxX = Float.NEGATIVE_INFINITY;
        float localMaxY = Float.NEGATIVE_INFINITY;
        float localMaxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < this.quadPositions.length; i += 3) {
            float x = requireFinite(this.quadPositions[i], "quad x");
            float y = requireFinite(this.quadPositions[i + 1], "quad y");
            float z = requireFinite(this.quadPositions[i + 2], "quad z");
            localMinX = Math.min(localMinX, x);
            localMinY = Math.min(localMinY, y);
            localMinZ = Math.min(localMinZ, z);
            localMaxX = Math.max(localMaxX, x);
            localMaxY = Math.max(localMaxY, y);
            localMaxZ = Math.max(localMaxZ, z);
        }

        this.minX = checkedWorldBound(worldX, localMinX, "minX");
        this.minY = checkedWorldBound(worldY, localMinY, "minY");
        this.minZ = checkedWorldBound(worldZ, localMinZ, "minZ");
        this.maxX = checkedWorldBound(worldX, localMaxX, "maxX");
        this.maxY = checkedWorldBound(worldY, localMaxY, "maxY");
        this.maxZ = checkedWorldBound(worldZ, localMaxZ, "maxZ");
    }

    public long instanceId() {
        return instanceId;
    }

    public String dimensionId() {
        return dimensionId;
    }

    public String entityTypeId() {
        return entityTypeId;
    }

    public double worldX() {
        return worldX;
    }

    public double worldY() {
        return worldY;
    }

    public double worldZ() {
        return worldZ;
    }

    public double minX() {
        return minX;
    }

    public double minY() {
        return minY;
    }

    public double minZ() {
        return minZ;
    }

    public double maxX() {
        return maxX;
    }

    public double maxY() {
        return maxY;
    }

    public double maxZ() {
        return maxZ;
    }

    public int quadCount() {
        return quadPositions.length / 12;
    }

    public float[] copyQuadPositions() {
        return quadPositions.clone();
    }

    public float[] copyQuadUvs() {
        return quadUvs.clone();
    }

    public boolean geometryEquals(DynamicEntitySnapshot other) {
        return other != null
                && instanceId == other.instanceId
                && dimensionId.equals(other.dimensionId)
                && entityTypeId.equals(other.entityTypeId)
                && Double.doubleToLongBits(worldX) == Double.doubleToLongBits(other.worldX)
                && Double.doubleToLongBits(worldY) == Double.doubleToLongBits(other.worldY)
                && Double.doubleToLongBits(worldZ) == Double.doubleToLongBits(other.worldZ)
                && Arrays.equals(quadPositions, other.quadPositions)
                && Arrays.equals(quadUvs, other.quadUvs);
    }

    private static double checkedWorldBound(double origin, float local, String name) {
        double value = origin + local;
        requireFinite(value, name);
        return value;
    }

    private static float requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return value;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
