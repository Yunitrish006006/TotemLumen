package dev.totem.lumen.render;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable stage/resource contract used to keep future renderer work from growing another
 * all-in-one shader.
 *
 * <p>The plan validates only data ownership/dependencies. Vulkan barriers, layouts and concrete
 * texture/buffer formats belong to each stage implementation.</p>
 */
public final class StagedRenderPlan {
    public record Pass(
            LumenRenderStage stage,
            Set<LumenStageResource> reads,
            Set<LumenStageResource> writes
    ) {
        public Pass {
            reads = Set.copyOf(reads);
            writes = Set.copyOf(writes);
            if (!disjoint(reads, writes)) {
                throw new IllegalArgumentException("Stage cannot read and overwrite the same logical resource: " + stage);
            }
        }

        private static boolean disjoint(Set<?> left, Set<?> right) {
            for (Object value : left) if (right.contains(value)) return false;
            return true;
        }
    }

    private final Set<LumenStageResource> externalInputs;
    private final List<Pass> passes;

    public StagedRenderPlan(Set<LumenStageResource> externalInputs, List<Pass> passes) {
        this.externalInputs = Set.copyOf(externalInputs);
        this.passes = List.copyOf(passes);
        validate();
    }

    public Set<LumenStageResource> externalInputs() {
        return externalInputs;
    }

    public List<Pass> passes() {
        return passes;
    }

    public List<LumenRenderStage> stages() {
        return passes.stream().map(Pass::stage).toList();
    }

    private void validate() {
        EnumSet<LumenRenderStage> seenStages = EnumSet.noneOf(LumenRenderStage.class);
        EnumSet<LumenStageResource> available = externalInputs.isEmpty()
                ? EnumSet.noneOf(LumenStageResource.class)
                : EnumSet.copyOf(externalInputs);
        EnumSet<LumenStageResource> produced = EnumSet.noneOf(LumenStageResource.class);

        for (Pass pass : passes) {
            if (!seenStages.add(pass.stage())) {
                throw new IllegalArgumentException("Duplicate render stage: " + pass.stage());
            }
            if (!available.containsAll(pass.reads())) {
                var missing = EnumSet.copyOf(pass.reads());
                missing.removeAll(available);
                throw new IllegalArgumentException("Stage " + pass.stage() + " reads unavailable resources: " + missing);
            }
            for (LumenStageResource output : pass.writes()) {
                if (!produced.add(output)) {
                    throw new IllegalArgumentException("Multiple stages own logical resource: " + output);
                }
                if (externalInputs.contains(output)) {
                    throw new IllegalArgumentException("Stage overwrites external input: " + output);
                }
            }
            available.addAll(pass.writes());
        }
    }

    /**
     * Current owned Raster surface path represented as explicit coarse stages.
     *
     * <p>It deliberately has no MATERIAL_RESOLVE/DIRECT_LIGHT/REFLECTION/TEMPORAL/DENOISE pass yet.
     * Those stages must be added as separate producers rather than folded into INDIRECT_GI.</p>
     */
    public static StagedRenderPlan rasterOwnedSurfacePath() {
        List<Pass> passes = new ArrayList<>();
        passes.add(new Pass(
                LumenRenderStage.SURFACE_CAPTURE,
                Set.of(LumenStageResource.NATIVE_DEPTH),
                Set.of(LumenStageResource.SURFACE)
        ));
        passes.add(new Pass(
                LumenRenderStage.INDIRECT_GI,
                Set.of(LumenStageResource.SURFACE, LumenStageResource.VOXEL_SCENE),
                Set.of(LumenStageResource.INDIRECT_RADIANCE)
        ));
        passes.add(new Pass(
                LumenRenderStage.COMPOSITE,
                Set.of(LumenStageResource.SURFACE, LumenStageResource.INDIRECT_RADIANCE),
                Set.of(LumenStageResource.FINAL_COLOR)
        ));
        return new StagedRenderPlan(
                Set.of(LumenStageResource.NATIVE_DEPTH, LumenStageResource.VOXEL_SCENE),
                passes
        );
    }

    /** Development graph that inserts MATERIAL_RESOLVE without yet feeding it into lighting. */
    public static StagedRenderPlan rasterMaterialMetadataPath() {
        List<Pass> passes = new ArrayList<>();
        passes.add(new Pass(
                LumenRenderStage.SURFACE_CAPTURE,
                Set.of(LumenStageResource.NATIVE_DEPTH),
                Set.of(LumenStageResource.SURFACE)
        ));
        passes.add(new Pass(
                LumenRenderStage.MATERIAL_RESOLVE,
                Set.of(LumenStageResource.SURFACE, LumenStageResource.VOXEL_SCENE),
                Set.of(LumenStageResource.MATERIAL)
        ));
        passes.add(new Pass(
                LumenRenderStage.INDIRECT_GI,
                Set.of(LumenStageResource.SURFACE, LumenStageResource.VOXEL_SCENE),
                Set.of(LumenStageResource.INDIRECT_RADIANCE)
        ));
        passes.add(new Pass(
                LumenRenderStage.COMPOSITE,
                Set.of(LumenStageResource.SURFACE, LumenStageResource.INDIRECT_RADIANCE),
                Set.of(LumenStageResource.FINAL_COLOR)
        ));
        return new StagedRenderPlan(
                Set.of(LumenStageResource.NATIVE_DEPTH, LumenStageResource.VOXEL_SCENE),
                passes
        );
    }

    /**
     * Development graph with an independent DIRECT_LIGHT producer whose output is intentionally
     * not consumed by COMPOSITE yet. This validates stage ownership without changing final pixels.
     */
    public static StagedRenderPlan rasterDirectLightDiagnosticPath() {
        List<Pass> passes = new ArrayList<>();
        passes.add(new Pass(
                LumenRenderStage.SURFACE_CAPTURE,
                Set.of(LumenStageResource.NATIVE_DEPTH),
                Set.of(LumenStageResource.SURFACE)
        ));
        passes.add(new Pass(
                LumenRenderStage.MATERIAL_RESOLVE,
                Set.of(LumenStageResource.SURFACE, LumenStageResource.VOXEL_SCENE),
                Set.of(LumenStageResource.MATERIAL)
        ));
        passes.add(new Pass(
                LumenRenderStage.DIRECT_LIGHT,
                Set.of(
                        LumenStageResource.SURFACE,
                        LumenStageResource.MATERIAL,
                        LumenStageResource.VOXEL_SCENE),
                Set.of(LumenStageResource.DIRECT_RADIANCE)
        ));
        passes.add(new Pass(
                LumenRenderStage.INDIRECT_GI,
                Set.of(LumenStageResource.SURFACE, LumenStageResource.VOXEL_SCENE),
                Set.of(LumenStageResource.INDIRECT_RADIANCE)
        ));
        passes.add(new Pass(
                LumenRenderStage.COMPOSITE,
                Set.of(LumenStageResource.SURFACE, LumenStageResource.INDIRECT_RADIANCE),
                Set.of(LumenStageResource.FINAL_COLOR)
        ));
        return new StagedRenderPlan(
                Set.of(LumenStageResource.NATIVE_DEPTH, LumenStageResource.VOXEL_SCENE),
                passes
        );
    }
}
