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
 * Builds a compact set of emissive voxel-light descriptors plus fixed per-section local light lists.
 *
 * <p>The input is Minecraft-independent: section snapshots, stable GPU slot lookup and material
 * emission metadata. Each resident GPU section slot receives at most
 * {@link #MAX_LIGHTS_PER_SECTION} nearby light indices, so shaders never scan every light in the
 * scene. Area emitters keep the voxel-center position and are expanded to sampled emitting faces in
 * the shader. Point emitters carry an explicit block-local anchor so thin sources such as torches
 * illuminate from the flame instead of the voxel center.</p>
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
                (EmissionResolver) materialId -> {
                    int level = emissionForMaterialId.applyAsInt(materialId);
                    return new Emission(
                            level,
                            1.0f,
                            1.0f,
                            1.0f,
                            1.0f,
                            1.0f,
                            false,
                            0.5f,
                            0.5f,
                            0.5f
                    );
                }
        );
    }

    public static Result build(
            List<SectionSnapshot> sections,
            int slotCapacity,
            ToIntFunction<SectionKey> slotResolver,
            EmissionResolver emissionResolver
    ) {
        return build(sections, slotCapacity, slotResolver, emissionResolver, List.of());
    }

    /** Builds material lights together with externally supplied world lights. */
    public static Result build(
            List<SectionSnapshot> sections,
            int slotCapacity,
            ToIntFunction<SectionKey> slotResolver,
            EmissionResolver emissionResolver,
            List<PointLight> additionalLights
    ) {
        if (slotCapacity < 1) {
            throw new IllegalArgumentException("slotCapacity must be positive");
        }

        List<PointLight> lights = collectLights(sections, emissionResolver, additionalLights);
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
            EmissionResolver emissionResolver,
            List<PointLight> additionalLights
    ) {
        List<PointLight> lights = new ArrayList<>();
        for (PointLight light : additionalLights) {
            if (light != null) {
                lights.add(light);
                if (lights.size() >= MAX_GLOBAL_LIGHTS) {
                    return lights;
                }
            }
        }
        outer:
        for (SectionSnapshot section : sections) {
            for (int localY = 0; localY < SectionVoxelData.SIZE; localY++) {
                for (int localZ = 0; localZ < SectionVoxelData.SIZE; localZ++) {
                    for (int localX = 0; localX < SectionVoxelData.SIZE; localX++) {
                        // P14 packs geometry into the upper half of each GPU voxel word. CPU light
                        // extraction must resolve the raw material ID instead of consuming the
                        // packed upload word as a material-table index.
                        int materialId = section.voxels().materialId(localX, localY, localZ);
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
                        float radius = Math.max(
                                0.5f,
                                Math.max(2.0f, emission.level() + 0.5f) * emission.radiusScale()
                        );
                        lights.add(new PointLight(
                                blockX + emission.anchorX(),
                                blockY + emission.anchorY(),
                                blockZ + emission.anchorZ(),
                                radius,
                                emission.level() / 15.0f * emission.intensityScale(),
                                emission.r(),
                                emission.g(),
                                emission.b(),
                                blockX,
                                blockY,
                                blockZ,
                                emission.pointEmitter()
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

    public record Emission(
            int level,
            float r,
            float g,
            float b,
            float radiusScale,
            float intensityScale,
            boolean pointEmitter,
            float anchorX,
            float anchorY,
            float anchorZ
    ) {
        public Emission(int level, float r, float g, float b) {
            this(level, r, g, b, 1.0f, 1.0f, false, 0.5f, 0.5f, 0.5f);
        }

        public Emission(
                int level,
                float r,
                float g,
                float b,
                float radiusScale,
                float intensityScale
        ) {
            this(
                    level,
                    r,
                    g,
                    b,
                    radiusScale,
                    intensityScale,
                    false,
                    0.5f,
                    0.5f,
                    0.5f
            );
        }

        public Emission {
            if (level < 0 || level > 15) {
                throw new IllegalArgumentException("emission level must be in [0, 15]");
            }
            if (!Float.isFinite(radiusScale) || radiusScale < 0.0f || radiusScale > 8.0f) {
                throw new IllegalArgumentException("radiusScale must be finite and in [0, 8]");
            }
            if (!Float.isFinite(intensityScale) || intensityScale < 0.0f || intensityScale > 8.0f) {
                throw new IllegalArgumentException("intensityScale must be finite and in [0, 8]");
            }
            if (!normalized(anchorX) || !normalized(anchorY) || !normalized(anchorZ)) {
                throw new IllegalArgumentException("light anchor components must be in [0, 1]");
            }
        }

        private static boolean normalized(float value) {
            return Float.isFinite(value) && value >= 0.0f && value <= 1.0f;
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
            int blockZ,
            boolean pointEmitter
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
