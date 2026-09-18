package dev.totem.lumen.gpu;

import dev.totem.lumen.scene.SectionKey;
import dev.totem.lumen.scene.SectionSnapshot;
import dev.totem.lumen.scene.SectionVoxelData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GpuSectionLightListsTest {
    @Test
    void extractsEmissiveVoxelAndAssignsNearbySections() {
        SectionSnapshot source = sectionWithLights(
                new SectionKey("minecraft:overworld", 0, 0, 0),
                new int[][]{{15, 8, 8, 1}}
        );
        SectionSnapshot neighbor = sectionWithLights(
                new SectionKey("minecraft:overworld", 1, 0, 0),
                new int[][]{}
        );

        var result = GpuSectionLightLists.build(
                List.of(source, neighbor),
                2,
                key -> key.x(),
                (IntUnaryOperator) materialId -> materialId == 1 ? 14 : 0
        );

        assertEquals(1, result.lights().size());
        var light = result.lights().getFirst();
        assertEquals(15.5f, light.x());
        assertEquals(8.5f, light.y());
        assertEquals(8.5f, light.z());
        assertEquals(14.5f, light.radius());
        assertEquals(1, result.countsBySlot()[0]);
        assertEquals(1, result.countsBySlot()[1]);
        assertEquals(0, result.indicesBySlot()[0][0]);
        assertEquals(0, result.indicesBySlot()[1][0]);
    }

    @Test
    void negativeSectionCoordinatesProduceCorrectWorldPositions() {
        SectionSnapshot source = sectionWithLights(
                new SectionKey("minecraft:overworld", -2, -1, 3),
                new int[][]{{0, 0, 0, 5}}
        );

        var result = GpuSectionLightLists.build(
                List.of(source),
                1,
                key -> 0,
                (IntUnaryOperator) materialId -> materialId == 5 ? 15 : 0
        );

        var light = result.lights().getFirst();
        assertEquals(-31.5f, light.x());
        assertEquals(-15.5f, light.y());
        assertEquals(48.5f, light.z());
        assertEquals(-32, light.blockX());
        assertEquals(-16, light.blockY());
        assertEquals(48, light.blockZ());
    }

    @Test
    void localListsAreCappedToEightNearestLights() {
        int[][] lights = new int[12][4];
        for (int index = 0; index < lights.length; index++) {
            lights[index] = new int[]{index, 8, 8, 1};
        }
        SectionSnapshot source = sectionWithLights(
                new SectionKey("minecraft:overworld", 0, 0, 0),
                lights
        );

        var result = GpuSectionLightLists.build(
                List.of(source),
                1,
                key -> 0,
                (IntUnaryOperator) materialId -> materialId == 1 ? 15 : 0
        );

        assertEquals(12, result.lights().size());
        assertEquals(GpuSectionLightLists.MAX_LIGHTS_PER_SECTION, result.countsBySlot()[0]);
        assertEquals(GpuSectionLightLists.MAX_LIGHTS_PER_SECTION, result.maxLightsInSection());
        for (int index = 0; index < GpuSectionLightLists.MAX_LIGHTS_PER_SECTION; index++) {
            assertTrue(result.indicesBySlot()[0][index] >= 0);
        }
    }

    @Test
    void coloredEmissionIsPreservedInPointLightRecord() {
        SectionSnapshot source = sectionWithLights(
                new SectionKey("minecraft:overworld", 0, 0, 0),
                new int[][]{{4, 5, 6, 9}}
        );

        var result = GpuSectionLightLists.build(
                List.of(source),
                1,
                key -> 0,
                (GpuSectionLightLists.EmissionResolver) materialId -> materialId == 9
                        ? new GpuSectionLightLists.Emission(12, 0.2f, 0.7f, 1.0f)
                        : new GpuSectionLightLists.Emission(0, 0.0f, 0.0f, 0.0f)
        );

        var light = result.lights().getFirst();
        assertEquals(0.2f, light.r());
        assertEquals(0.7f, light.g());
        assertEquals(1.0f, light.b());
        assertEquals(12.0f / 15.0f, light.intensity());
    }

    @Test
    void appliesDataDrivenRadiusAndIntensityScales() {
        SectionSnapshot source = sectionWithLights(
                new SectionKey("minecraft:overworld", 0, 0, 0),
                new int[][]{{8, 8, 8, 3}}
        );

        var result = GpuSectionLightLists.build(
                List.of(source),
                1,
                key -> 0,
                (GpuSectionLightLists.EmissionResolver) materialId -> materialId == 3
                        ? new GpuSectionLightLists.Emission(
                                12,
                                1.0f,
                                0.5f,
                                0.25f,
                                0.5f,
                                2.0f
                        )
                        : new GpuSectionLightLists.Emission(0, 0.0f, 0.0f, 0.0f)
        );

        var light = result.lights().getFirst();
        assertEquals(6.25f, light.radius());
        assertEquals(1.6f, light.intensity());
        assertEquals(1.0f, light.r());
        assertEquals(0.5f, light.g());
        assertEquals(0.25f, light.b());
    }

    @Test
    void pointEmitterUsesExplicitBlockLocalAnchor() {
        SectionSnapshot source = sectionWithLights(
                new SectionKey("minecraft:overworld", 2, 3, -1),
                new int[][]{{4, 5, 6, 7}}
        );

        var result = GpuSectionLightLists.build(
                List.of(source),
                1,
                key -> 0,
                (GpuSectionLightLists.EmissionResolver) materialId -> materialId == 7
                        ? new GpuSectionLightLists.Emission(
                                14,
                                1.0f,
                                0.55f,
                                0.22f,
                                1.0f,
                                1.0f,
                                true,
                                0.50f,
                                0.70f,
                                0.23f
                        )
                        : new GpuSectionLightLists.Emission(0, 0.0f, 0.0f, 0.0f)
        );

        var light = result.lights().getFirst();
        assertEquals(36.50f, light.x(), 0.0001f);
        assertEquals(53.70f, light.y(), 0.0001f);
        assertEquals(-9.77f, light.z(), 0.0001f);
        assertTrue(light.pointEmitter());
    }

    private static SectionSnapshot sectionWithLights(SectionKey key, int[][] lightVoxels) {
        int[] ids = new int[SectionVoxelData.VOXEL_COUNT];
        for (int[] light : lightVoxels) {
            ids[SectionVoxelData.index(light[0], light[1], light[2])] = light[3];
        }
        return new SectionSnapshot(key, 1, new SectionVoxelData(ids));
    }
}
