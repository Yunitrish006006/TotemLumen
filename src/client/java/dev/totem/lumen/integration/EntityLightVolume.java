package dev.totem.lumen.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;

/** Bounded grid of the terrain lighting captured with the entity's interpolated render state. */
public final class EntityLightVolume {
    private final AABB bounds;
    private final int nx, ny, nz;
    private final int[] tints;

    private EntityLightVolume(AABB bounds) {
        this.bounds = bounds;
        nx = samples(bounds.getXsize());
        ny = samples(bounds.getYsize());
        nz = samples(bounds.getZsize());
        tints = new int[nx * ny * nz];
    }

    static EntityLightVolume capture(LivingEntity entity, float partialTick, int emissionFloor) {
        AABB box = entity.getInterpolatedBoundingBox(partialTick);
        if (box.getXsize() < 1.25 && box.getZsize() < 1.25 && box.getYsize() < 2.0) return null;
        if (entity instanceof EnderDragon dragon) {
            for (var part : dragon.getSubEntities()) box = box.minmax(part.getInterpolatedBoundingBox(partialTick));
        }
        // Models can extend past the collision box, especially at their head and tail.
        var field = new EntityLightVolume(box.inflate(0.5));
        var block = new BlockPos.MutableBlockPos();
        boolean uniform = true;
        for (int k = 0; k < field.nz; k++) {
            double z = Mth.lerp((double) k / (field.nz - 1), field.bounds.minZ, field.bounds.maxZ);
            for (int j = 0; j < field.ny; j++) {
                double y = Mth.lerp((double) j / (field.ny - 1), field.bounds.minY, field.bounds.maxY);
                for (int i = 0; i < field.nx; i++) {
                    double x = Mth.lerp((double) i / (field.nx - 1), field.bounds.minX, field.bounds.maxX);
                    int sky = entity.level().getBrightness(LightLayer.SKY, block.set(x, y, z));
                    int idx = (k * field.ny + j) * field.nx + i;
                    field.tints[idx] = VanillaRgbLighting.modelTint(x, y, z, sky, emissionFloor);
                    uniform &= field.tints[idx] == field.tints[0];
                }
            }
        }
        return uniform ? null : field;
    }

    // At most 125 probes even for a dragon. Finer spacing on ordinary mobs preserves local gradients.
    private static int samples(double extent) {
        return Mth.clamp(Mth.ceil(extent) + 1, 2, 5);
    }

    int tintAt(double x, double y, double z) {
        double fx = Mth.clamp((x - bounds.minX) / bounds.getXsize(), 0, 1) * (nx - 1);
        double fy = Mth.clamp((y - bounds.minY) / bounds.getYsize(), 0, 1) * (ny - 1);
        double fz = Mth.clamp((z - bounds.minZ) / bounds.getZsize(), 0, 1) * (nz - 1);
        int i = Math.min((int) fx, nx - 2), j = Math.min((int) fy, ny - 2), k = Math.min((int) fz, nz - 2);
        int base = (k * ny + j) * nx + i;
        return ARGB.srgbLerp((float) (fz - k),
                plane(base, (float) (fx - i), (float) (fy - j)),
                plane(base + nx * ny, (float) (fx - i), (float) (fy - j)));
    }

    private int plane(int base, float x, float y) {
        return ARGB.srgbLerp(y, ARGB.srgbLerp(x, tints[base], tints[base + 1]),
                ARGB.srgbLerp(x, tints[base + nx], tints[base + nx + 1]));
    }
}
