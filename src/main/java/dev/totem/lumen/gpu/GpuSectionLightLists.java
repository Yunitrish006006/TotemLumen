package dev.totem.lumen.gpu;

import dev.totem.lumen.scene.SectionKey;
import dev.totem.lumen.scene.SectionSnapshot;
import dev.totem.lumen.scene.SectionVoxelData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntUnaryOperator;
import java.util.function.ToIntFunction;

/**
 * Builds a compact set of emissive voxel lights plus fixed per-section local light lists.
 *
 * <p>The input is Minecraft-independent: section snapshots, stable GPU slot lookup and material
 * emission metadata. Each resident GPU section slot receives at most
 * {@link #MAX_LIGHTS_PER_SECTION} nearby light indices, so shaders never scan every light in the
 * scene.</p>
 */
public final class GpuSectionLightLists {
    public static final int MAX_GLOBAL_LIGHTS = 256;
    public static final int MAX_LIGHTS_PER_SECTION = 8;

    private GpuSectionLightLists() {
    }

    /** Compatibility overload used by older tests/callers; emissive RGB defaults to white. */
    public static Result build(
            List<SectionSnapshot> sections,
            int slotCapacity,
            ToIntFunction<SectionKey> slotResolver,
            IntUnaryOperator emissionForMaterialId
    ) {
        return build(
                sections,
                slotCapacity,
                slotResolver,
                materialId -> {
                    int level = emissionForMaterialId.applyAsInt(materialId);
                    return new Emission(level, 1.0f, 1.0f, 1.0f);
                }
        );
    }

    public static Result build(
            List<SectionSnapshot> sections,
            int slotCapacity,
            ToIntFunction<SectionKey> slotResolver,
            EmissionResolver emissionResolver
    ) {
        if (slotCapacity < 1) {
            throw new IllegalArgumentException("slotCapacity must be positive");
        }

        List<PointLight> lights = collectLights(sections, emissionResolver);
        int[][] indicesBySlot = new int[slotCapacity][MAX_LIGHTS_PER_SECTION];
        int[] countsBySlot = new int[slotCapacity];
        for (int[] indices : indicesBySlot) {
            Arrays.fill(indices, -1);
        }

        int maxLightsInSection = 0;
        int populatedLists = 0;
        for (SectionSnapshot section : sections) {
            int slot = slotResolver.applyAsInt(section.key());
            if (slot < 0 || slot >= slotCapacity) {
                throw new IllegalArgumentException("Invalid GPU slot " + slot + " for " + section.key());
            }

            List<LightDistance> candidates = new ArrayList<>();
            for (int lightIndex = 0; lightIndex < lights.size(); lightIndex++) {
                PointLight light = lights.get(lightIndex);
                double distanceSquared = distanceSquaredToSectionAabb(light, section.key());
                if (distanceSquared <= (double) light.radius() * light.radius()) {
                    candidates.add(new LightDistance(lightIndex, distanceSquared));
                }
            }
            candidates.sort(Comparator
                    .comparingDouble(LightDistance::distanceSquared)
                    .thenComparingInt(LightDistance::lightIndex));

            int count = Math.min(MAX_LIGHTS_PER_SECTION, candidates.size());
            countsBySlot[slot] = count;
            for (int index = 0; index < count; index++) {
                indicesBySlot[slot][index] = candidates.get(index).lightIndex();
            }
            if (count > 0) {
                populatedLists++;
            }
            maxLightsInSection = Math.max(maxLightsInSection, count);
        }

        return new Result(
                List.copyOf(lights),
                countsBySlot,
                indicesBySlot,
                maxLightsInSection,
                populatedLists
        );
    }

    private static List<PointLight> collectLights(
            List<SectionSnapshot> sections,
            EmissionResolver emissionResolver
    ) {
        List<PointLight> lights = new ArrayList<>();
        outer:
        for (SectionSnapshot section : sections) {
            int[] materialIds = section.voxels().copyMaterialIds();
            for (int localY = 0; localY < SectionVoxelData.SIZE; localY++) {
                for (int localZ = 0; localZ < SectionVoxelData.SIZE; localZ++) {
                    for (int localX = 0; localX < SectionVoxelData.SIZE; localX++) {
                        int materialId = materialIds[SectionVoxelData.index(localX, localY, localZ)];
                        if (materialId == 0) {
                            continue;
                        }
                        Emission emission = emissionResolver.resolve(materialId);
                        if (emission == null || emission.level() <= 0) {
                            continue;
                        }

                        int blockX = section.key().x() * SectionVoxelData.SIZE + localX;
                        int blockY = section.key().y() * SectionVoxelData.SIZE + localY;
                        int blockZ = section.key().z() * SectionVoxelData.SIZE + localZ;
                        float radius = Math.max(2.0f, emission.level() + 0.5f);
                        lights.add(new PointLight(
                                blockX + 0.5f,
                                blockY + 0.5f,
                                blockZ + 0.5f,
                                radius,
                                emission.level() / 15.0f,
                                emission.r(),
                                emission.g(),
                                emission.b(),
                                blockX,
                                blockY,
                                blockZ
                        ));
                        if (lights.size() >= MAX_GLOBAL_LIGHTS) {
                            break outer;
                        }
                    }
                }
            }
        }
        return lights;
    }

    private static double distanceSquaredToSectionAabb(PointLight light, SectionKey section) {
        double minX = section.x() * 16.0;
        double minY = section.y() * 16.0;
        double minZ = section.z() * 16.0;
        double maxX = minX + 16.0;
        double maxY = minY + 16.0;
        double maxZ = minZ + 16.0;

        double dx = axisDistance(light.x(), minX, maxX);
        double dy = axisDistance(light.y(), minY, maxY);
        double dz = axisDistance(light.z(), minZ, maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double axisDistance(double value, double min, double max) {
        if (value < min) return min - value;
        if (value > max) return value - max;
        return 0.0;
    }

    @FunctionalInterface
    public interface EmissionResolver {
        Emission resolve(int materialId);
    }

    public record Emission(int level, float r, float g, float b) {
        public Emission {
            if (level < 0 || level > 15) {
                throw new IllegalArgumentException("emission level must be in [0, 15]");
            }
        }
    }

    private record LightDistance(int lightIndex, double distanceSquared) {
    }

    public record PointLight(
            float x,
            float y,
            float z,
            float radius,
            float intensity,
            float r,
            float g,
            float b,
            int blockX,
            int blockY,
            int blockZ
    ) {
    }

    public record Result(
            List<PointLight> lights,
            int[] countsBySlot,
            int[][] indicesBySlot,
            int maxLightsInSection,
            int populatedLists
    ) {
        public Result {
            countsBySlot = countsBySlot.clone();
            int[][] copied = new int[indicesBySlot.length][];
            for (int index = 0; index < indicesBySlot.length; index++) {
                copied[index] = indicesBySlot[index].clone();
            }
            indicesBySlot = copied;
        }

        @Override
        public int[] countsBySlot() {
            return countsBySlot.clone();
        }

        @Override
        public int[][] indicesBySlot() {
            int[][] copied = new int[indicesBySlot.length][];
            for (int index = 0; index < indicesBySlot.length; index++) {
                copied[index] = indicesBySlot[index].clone();
            }
            return copied;
        }
    }
}
