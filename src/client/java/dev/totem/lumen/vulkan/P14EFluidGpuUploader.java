package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.gpu.FluidSceneSelector;
import dev.totem.lumen.gpu.GpuDynamicEntityScene;
import dev.totem.lumen.gpu.GpuFluidScene;
import dev.totem.lumen.integration.FluidRenderGeometryCache;
import dev.totem.lumen.scene.FluidGeometrySnapshot;
import dev.totem.lumen.scene.SectionKey;
import dev.totem.lumen.scene.SectionSnapshot;

import java.nio.ByteBuffer;
import java.util.List;

/** Packs/copies the P14E exact-fluid scene tail independently from static voxel payloads. */
public final class P14EFluidGpuUploader {
    private static final int HISTORY_READ_BASE_WORD = 24;
    private static final int PREVIOUS_FRAME_VALID_WORD = 26;

    private static volatile long lastBaseByteOffset = -1L;
    private static volatile long lastCopyBytes;
    private static volatile List<SectionKey> residentSections = List.of();
    private static long lastObservedRevision = Long.MIN_VALUE;
    private static List<FluidGeometrySnapshot> lastPackedFluids = List.of();
    private static boolean copyPending;
    private static int lastLoggedCellCount = -1;
    private static int lastLoggedQuadCount = -1;
    private static int lastLoggedMaxProbe = -1;
    private static int lastLoggedResidentCellCount = -1;
    private static int lastLoggedDroppedCellCount = -1;

    private P14EFluidGpuUploader() {
    }

    public static synchronized void pack(ByteBuffer buffer, List<SectionSnapshot> sections) {
        residentSections = sections.stream().map(SectionSnapshot::key).toList();
        FluidRenderGeometryCache.SceneState state = FluidRenderGeometryCache.sceneState();
        FluidSceneSelector.Selection selection = select(state);
        packSelection(buffer, state.revision(), selection);
    }

    public static synchronized boolean packIfDirty(ByteBuffer buffer) {
        FluidRenderGeometryCache.SceneState state = FluidRenderGeometryCache.sceneState();
        if (state.revision() == lastObservedRevision) return false;

        FluidSceneSelector.Selection selection = select(state);
        lastObservedRevision = state.revision();
        if (selection.fluids().equals(lastPackedFluids)) {
            return false;
        }

        packSelection(buffer, state.revision(), selection);
        invalidateHistoryRead(buffer);
        return true;
    }

    private static FluidSceneSelector.Selection select(FluidRenderGeometryCache.SceneState state) {
        return FluidSceneSelector.select(
                state.fluids(),
                residentSections,
                GpuFluidScene.MAX_FLUID_CELLS,
                GpuFluidScene.MAX_FLUID_QUADS
        );
    }

    private static void packSelection(
            ByteBuffer buffer,
            long observedRevision,
            FluidSceneSelector.Selection selection
    ) {
        int pixelBaseWord = buffer.getInt(3 * Integer.BYTES);
        int capacityWidth = buffer.getInt(51 * Integer.BYTES);
        int capacityHeight = buffer.getInt(52 * Integer.BYTES);
        int pixelCount = Math.multiplyExact(capacityWidth, capacityHeight);
        int p14BaseWord = Math.addExact(pixelBaseWord, pixelCount);
        int p17BaseWord = Math.addExact(p14BaseWord, P14ModelMeshGpuLayout.MAX_STORAGE_WORDS);
        int fluidBaseWord = Math.addExact(p17BaseWord, GpuDynamicEntityScene.MAX_STORAGE_WORDS);

        GpuFluidScene.PackResult packed = GpuFluidScene.pack(buffer, fluidBaseWord, selection.fluids());
        lastBaseByteOffset = (long) fluidBaseWord * Integer.BYTES;
        lastCopyBytes = packed.usedBytes();
        lastObservedRevision = observedRevision;
        lastPackedFluids = selection.fluids();
        copyPending = true;

        boolean diagnosticsChanged = lastLoggedCellCount != packed.cellCount()
                || lastLoggedQuadCount != packed.totalQuads()
                || lastLoggedMaxProbe != packed.maxProbe()
                || lastLoggedResidentCellCount != selection.residentCellCount()
                || lastLoggedDroppedCellCount != selection.droppedCellCount();
        if (diagnosticsChanged) {
            lastLoggedCellCount = packed.cellCount();
            lastLoggedQuadCount = packed.totalQuads();
            lastLoggedMaxProbe = packed.maxProbe();
            lastLoggedResidentCellCount = selection.residentCellCount();
            lastLoggedDroppedCellCount = selection.droppedCellCount();
            TotemLumenClient.LOGGER.info(
                    "P14E fluid GPU scene: cells={}, quads={}, residentCells={}, residentQuads={}, droppedCells={}, droppedQuads={}, maxProbe={}, bytes={}, maxBytes={}",
                    packed.cellCount(),
                    packed.totalQuads(),
                    selection.residentCellCount(),
                    selection.residentQuadCount(),
                    selection.droppedCellCount(),
                    selection.droppedQuadCount(),
                    packed.maxProbe(),
                    lastCopyBytes,
                    GpuFluidScene.MAX_STORAGE_BYTES
            );
            if (selection.truncated()) {
                TotemLumenClient.LOGGER.warn(
                        "P14E resident fluid scene exceeded bounded GPU capacity; keeping nearest resident geometry and dropping farther cells: residentCells={}, selectedCells={}, residentQuads={}, selectedQuads={}, maxCells={}, maxQuads={}",
                        selection.residentCellCount(),
                        packed.cellCount(),
                        selection.residentQuadCount(),
                        packed.totalQuads(),
                        GpuFluidScene.MAX_FLUID_CELLS,
                        GpuFluidScene.MAX_FLUID_QUADS
                );
            }
        }
    }

    private static void invalidateHistoryRead(ByteBuffer buffer) {
        buffer.putInt(HISTORY_READ_BASE_WORD * Integer.BYTES, 0);
        buffer.putInt(PREVIOUS_FRAME_VALID_WORD * Integer.BYTES, 0);
    }

    public static long lastBaseByteOffset() {
        return lastBaseByteOffset;
    }

    public static long lastCopyBytes() {
        return lastCopyBytes;
    }

    public static synchronized boolean consumeCopyPending() {
        boolean pending = copyPending;
        copyPending = false;
        return pending;
    }
}
