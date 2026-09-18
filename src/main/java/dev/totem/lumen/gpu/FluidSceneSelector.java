package dev.totem.lumen.gpu;

import dev.totem.lumen.scene.FluidGeometrySnapshot;
import dev.totem.lumen.scene.SectionKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Selects the bounded exact-fluid subset that belongs to the renderer's ordered resident sections. */
public final class FluidSceneSelector {
    private FluidSceneSelector() {
    }

    public static Selection select(
            List<FluidGeometrySnapshot> fluids,
            List<SectionKey> orderedResidentSections,
            int maxCells,
            int maxQuads
    ) {
        Objects.requireNonNull(fluids, "fluids");
        Objects.requireNonNull(orderedResidentSections, "orderedResidentSections");
        if (maxCells < 0 || maxQuads < 0) {
            throw new IllegalArgumentException("fluid scene capacities must be non-negative");
        }

        Map<SectionCoord, Integer> rankBySection = new HashMap<>();
        for (int index = 0; index < orderedResidentSections.size(); index++) {
            SectionKey key = Objects.requireNonNull(orderedResidentSections.get(index), "resident section");
            rankBySection.putIfAbsent(
                    new SectionCoord(key.dimensionId(), key.x(), key.y(), key.z()),
                    index
            );
        }

        ArrayList<RankedFluid> resident = new ArrayList<>();
        int residentQuads = 0;
        for (FluidGeometrySnapshot fluid : fluids) {
            Objects.requireNonNull(fluid, "fluid");
            Integer rank = rankBySection.get(new SectionCoord(
                    fluid.dimensionId(),
                    fluid.sectionX(),
                    fluid.sectionY(),
                    fluid.sectionZ()
            ));
            if (rank == null) continue;
            resident.add(new RankedFluid(rank, fluid));
            residentQuads = Math.addExact(residentQuads, fluid.quadCount());
        }

        resident.sort(Comparator
                .comparingInt(RankedFluid::rank)
                .thenComparingInt(value -> value.fluid().blockX())
                .thenComparingInt(value -> value.fluid().blockY())
                .thenComparingInt(value -> value.fluid().blockZ()));

        ArrayList<FluidGeometrySnapshot> selected = new ArrayList<>(Math.min(maxCells, resident.size()));
        int selectedQuads = 0;
        for (RankedFluid ranked : resident) {
            FluidGeometrySnapshot fluid = ranked.fluid();
            if (selected.size() >= maxCells || selectedQuads + fluid.quadCount() > maxQuads) {
                break;
            }
            selected.add(fluid);
            selectedQuads += fluid.quadCount();
        }

        return new Selection(
                List.copyOf(selected),
                resident.size(),
                residentQuads,
                resident.size() - selected.size(),
                residentQuads - selectedQuads
        );
    }

    private record SectionCoord(String dimensionId, int x, int y, int z) {
    }

    private record RankedFluid(int rank, FluidGeometrySnapshot fluid) {
    }

    public record Selection(
            List<FluidGeometrySnapshot> fluids,
            int residentCellCount,
            int residentQuadCount,
            int droppedCellCount,
            int droppedQuadCount
    ) {
        public boolean truncated() {
            return droppedCellCount > 0 || droppedQuadCount > 0;
        }
    }
}
