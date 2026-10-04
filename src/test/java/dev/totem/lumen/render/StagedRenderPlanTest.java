package dev.totem.lumen.render;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StagedRenderPlanTest {
    @Test
    void ownedRasterSurfacePathHasExplicitCoarseStageBoundaries() {
        var plan = StagedRenderPlan.rasterOwnedSurfacePath();
        assertEquals(List.of(
                LumenRenderStage.SURFACE_CAPTURE,
                LumenRenderStage.INDIRECT_GI,
                LumenRenderStage.COMPOSITE
        ), plan.stages());
        assertEquals(Set.of(LumenStageResource.NATIVE_DEPTH, LumenStageResource.VOXEL_SCENE),
                plan.externalInputs());
    }


    @Test
    void materialMetadataPathAddsIndependentResolveBoundary() {
        var plan = StagedRenderPlan.rasterMaterialMetadataPath();
        assertEquals(List.of(
                LumenRenderStage.SURFACE_CAPTURE,
                LumenRenderStage.MATERIAL_RESOLVE,
                LumenRenderStage.INDIRECT_GI,
                LumenRenderStage.COMPOSITE
        ), plan.stages());
        var material = plan.passes().stream()
                .filter(pass -> pass.stage() == LumenRenderStage.MATERIAL_RESOLVE)
                .findFirst().orElseThrow();
        assertEquals(Set.of(LumenStageResource.SURFACE, LumenStageResource.VOXEL_SCENE),
                material.reads());
        assertEquals(Set.of(LumenStageResource.MATERIAL), material.writes());
    }

    @Test
    void rejectsReadsBeforeProducer() {
        var pass = new StagedRenderPlan.Pass(
                LumenRenderStage.INDIRECT_GI,
                Set.of(LumenStageResource.MATERIAL),
                Set.of(LumenStageResource.INDIRECT_RADIANCE)
        );
        var error = assertThrows(IllegalArgumentException.class,
                () -> new StagedRenderPlan(Set.of(), List.of(pass)));
        assertTrue(error.getMessage().contains("MATERIAL"));
    }

    @Test
    void rejectsDuplicateResourceOwnership() {
        var first = new StagedRenderPlan.Pass(
                LumenRenderStage.DIRECT_LIGHT,
                Set.of(),
                Set.of(LumenStageResource.DIRECT_RADIANCE)
        );
        var second = new StagedRenderPlan.Pass(
                LumenRenderStage.INDIRECT_GI,
                Set.of(),
                Set.of(LumenStageResource.DIRECT_RADIANCE)
        );
        assertThrows(IllegalArgumentException.class,
                () -> new StagedRenderPlan(Set.of(), List.of(first, second)));
    }

    @Test
    void rejectsDuplicateStageIdentity() {
        var first = new StagedRenderPlan.Pass(
                LumenRenderStage.DIRECT_LIGHT,
                Set.of(),
                Set.of(LumenStageResource.DIRECT_RADIANCE)
        );
        var second = new StagedRenderPlan.Pass(
                LumenRenderStage.DIRECT_LIGHT,
                Set.of(),
                Set.of(LumenStageResource.INDIRECT_RADIANCE)
        );
        assertThrows(IllegalArgumentException.class,
                () -> new StagedRenderPlan(Set.of(), List.of(first, second)));
    }

    @Test
    void passCannotReadAndOverwriteSameLogicalResource() {
        assertThrows(IllegalArgumentException.class, () -> new StagedRenderPlan.Pass(
                LumenRenderStage.TEMPORAL,
                Set.of(LumenStageResource.INDIRECT_RADIANCE),
                Set.of(LumenStageResource.INDIRECT_RADIANCE)
        ));
    }
}
