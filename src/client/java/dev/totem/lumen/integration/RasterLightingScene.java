package dev.totem.lumen.integration;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.material.MaterialFlags;
import dev.totem.lumen.render.RasterLightingVolume;
import dev.totem.lumen.render.RasterLightingWindow;
import dev.totem.lumen.render.RasterMaterialRegistry;
import dev.totem.lumen.render.RendererSettings;
import dev.totem.lumen.scene.BlockGeometryCode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Arrays;
import java.util.HashMap;
import net.minecraft.world.level.block.state.BlockState;

/** Bounded rolling window, urgent edits plus fair refresh. No world reads during GPU recording. */
public final class RasterLightingScene {
    private static final RasterMaterialRegistry MATERIALS = new RasterMaterialRegistry();

    private static ClientLevel level;
    private static RasterLightingWindow window;
    private static long epoch, extracted;
    private static volatile RasterLightingVolume snapshot;
    private static volatile boolean refreshRequested;

    private RasterLightingScene() { }
    public static RasterLightingVolume snapshot() { return snapshot; }
    public static RasterMaterialRegistry.Snapshot materialSnapshot() { return MATERIALS.snapshot(); }
    public static void requestRefresh() { refreshRequested = true; }

    public static void clear() {
        if (level != null) TotemLumenClient.LOGGER.info("Raster ray scene stopped: extractedSections={}", extracted);
        level = null; window = null; snapshot = null; extracted = 0;
    }

    public static void onBlockChanged(ClientLevel changedLevel, net.minecraft.core.BlockPos pos) {
        if (!RendererSettings.rasterLightingEnabled() || level != changedLevel || window == null) return;
        if (window.invalidate(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4)) snapshot = window.snapshot();
    }

    /** On unload, discard stale occluders immediately; reload is captured under the normal budget. */
    public static void onChunkChanged(ClientLevel changedLevel, int sx, int sz) {
        if (!RendererSettings.rasterLightingEnabled() || level != changedLevel || window == null) return;
        for (int sy = 0; sy < RasterLightingVolume.SECTIONS; sy++)
            window.invalidate(sx, window.sectionY(sy * RasterLightingVolume.SECTIONS * RasterLightingVolume.SECTIONS), sz);
        snapshot = window.snapshot();
    }

    public static void tick(Minecraft client) {
        if (!RendererSettings.rasterLightingEnabled() || RasterLightingRenderer.unavailable()
                || RasterSurfaceCapture.unavailable() || client.level == null || client.player == null) {
            if (level != null) clear();
            return;
        }
        // Camera entity can be detached from the player (spectator). No mutable reference escapes.
        var camera = client.getCameraEntity();
        if (camera == null) return;
        if (refreshRequested) {
            refreshRequested = false;
            clear();
        }
        int nx = RasterLightingVolume.origin(camera.getX());
        int ny = RasterLightingVolume.origin(camera.getY());
        int nz = RasterLightingVolume.origin(camera.getZ());
        if (level != client.level || window == null || !window.at(nx, ny, nz)) {
            window = new RasterLightingWindow(nx, ny, nz, ++epoch, level == client.level ? window : null);
            level = client.level;
            snapshot = window.snapshot(); // Rebase only same-world overlapping immutable data.
            TotemLumenClient.LOGGER.info("Raster ray window moved: retainedSections={}, capacity={}",
                    window.retainedSections(), RasterLightingVolume.SLOTS);
        }
        long started = System.nanoTime();
        for (int n = 0; n < RasterLightingWindow.SECTIONS_PER_TICK; n++) {
            int slot = window.next();
            int[] pixels = new int[4096];
            char[] materialIds = new char[4096];
            char[] surfaceSetIds = RasterMaterialResolveStage.enabled() ? new char[4096] : null;
            var chunk = level.getChunkSource().getChunk(window.sectionX(slot), window.sectionZ(slot), ChunkStatus.FULL, false);
            if (chunk != null) {
                int sectionIndex = chunk.getSectionIndexFromSectionY(window.sectionY(slot));
                if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
                    Arrays.fill(pixels, RasterLightingVolume.AIR);
                } else {
                    var section = chunk.getSections()[sectionIndex];
                    HashMap<BlockState, CellData> resolved = new HashMap<>();
                    BlockPos.MutableBlockPos worldPos = surfaceSetIds == null ? null : new BlockPos.MutableBlockPos();
                    int baseX = window.sectionX(slot) << 4;
                    int baseY = window.sectionY(slot) << 4;
                    int baseZ = window.sectionZ(slot) << 4;
                    for (int ly = 0; ly < 16; ly++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                        BlockState state = section.getBlockState(lx, ly, lz);
                        CellData cell = resolved.computeIfAbsent(state, RasterLightingScene::pack);
                        int index = RasterLightingVolume.index(lx, ly, lz);
                        pixels[index] = cell.voxel();
                        materialIds[index] = (char) cell.materialId();
                        if (surfaceSetIds != null && !state.isAir()) {
                            worldPos.set(baseX + lx, baseY + ly, baseZ + lz);
                            surfaceSetIds[index] = (char) texturedCubeSurfaceSetId(state, worldPos);
                        }
                    }
                }
            }
            RasterLightingVolume.Section old = window.section(slot);
            boolean changed = old == null;
            if (!changed) for (int i = 0; i < 4096; i++) {
                int surfaceSetId = surfaceSetIds == null ? 0 : surfaceSetIds[i];
                if (old.voxel(i) != pixels[i] || old.materialId(i) != materialIds[i]
                        || old.surfaceSetId(i) != surfaceSetId) {
                    changed = true;
                    break;
                }
            }
            window.put(slot, changed
                    ? new RasterLightingVolume.Section(
                            pixels, materialIds, surfaceSetIds, RasterDirectLightStage.requested())
                    : old);
            extracted++;
            if (System.nanoTime() - started >= 2_000_000L) break;
        }
        snapshot = window.snapshot();
        if (extracted == 2 || extracted % 1200 == 0) {
            TotemLumenClient.LOGGER.info("Raster ray extraction: sectionsPerTick=2, total={}, cpuMs={}, rayDistance={}",
                    extracted, (System.nanoTime() - started) / 1_000_000.0, RasterLightingVolume.RAY_DISTANCE);
        }
    }

    private static int texturedCubeSurfaceSetId(BlockState state, BlockPos pos) {
        try {
            int geometry = MinecraftBlockModelMeshResolver.geometryCode(state, level, pos);
            return BlockGeometryCode.family(geometry) == BlockGeometryCode.TEXTURED_CUBE
                    ? BlockGeometryCode.texturedCubeSurfaceSetId(geometry)
                    : 0;
        } catch (RuntimeException failure) {
            // Material metadata is optional development coverage. Unsupported model geometry keeps
            // its material ID but does not invent a textured-cube surface identity.
            return 0;
        }
    }

    private static CellData pack(BlockState state) {
        if (state.isAir()) return new CellData(RasterLightingVolume.AIR, 0);
        var material = MinecraftMaterialResolver.resolve(state);
        boolean solid = state.canOcclude() && !MaterialFlags.has(material.flags(), MaterialFlags.TRANSLUCENT);
        // Reuse the RGB profile's source rules (including gameplay-strength override and
        // visual +1 adjustment). This helper is stateless; it does not run RGB propagation.
        int voxel = RasterLightingVolume.packRgbSource(solid, ClientRgbVisualLightSource.packedFor(state));
        return new CellData(voxel, MATERIALS.register(material));
    }

    private record CellData(int voxel, int materialId) { }
}
