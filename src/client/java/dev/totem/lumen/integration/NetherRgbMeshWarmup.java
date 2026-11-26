package dev.totem.lumen.integration;

import dev.totem.lumen.render.RendererSettings;
import java.util.LinkedHashSet;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SectionUpdateRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;

/** Submit one already-loaded, lit section through the normal extraction and upload path. */
public final class NetherRgbMeshWarmup {
    private static final LinkedHashSet<Long> pending = new LinkedHashSet<>();
    private static long lastTick = Long.MIN_VALUE;

    private NetherRgbMeshWarmup() {}

    static void request(int x, int y, int z) {
        long key = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
        if (pending.add(key) && pending.size() > 96) pending.removeFirst();
    }

    static void clear() {
        pending.clear();
        lastTick = Long.MIN_VALUE;
    }

    public static void extract(ClientLevel level, SectionUpdateTracker tracker, LevelRenderer renderer,
                               LevelRenderState frame) {
        if (level == null || tracker == null || level.dimension() != Level.NETHER
                || RendererSettings.renderProfile() != RendererSettings.RenderProfile.MINECRAFT_RGB
                || !frame.cameraRenderState.initialized || level.getGameTime() == lastTick) return;
        lastTick = level.getGameTime();
        var view = renderer.viewArea();
        var dispatcher = renderer.sectionRenderDispatcher();
        if (view == null || dispatcher == null || dispatcher.getCompileQueueSize() > 8
                || dispatcher.getFreeBufferCount() == 0 || frame.sectionUpdateRenderStates.size() > 8) return;
        BlockPos camera = frame.cameraRenderState.blockPos;
        long own = SectionPos.asLong(camera);
        pending.add(own);
        long nearest = 0;
        double best = Double.POSITIVE_INFINITY;
        var iterator = pending.iterator();
        while (iterator.hasNext()) {
            long node = iterator.next();
            BlockPos pos = SectionPos.of(node).center();
            double distance = camera.distSqr(pos);
            var section = view.getRenderSectionAt(pos);
            var dirty = tracker.getDirtyState(node);
            if (distance > 64 * 64 || section == null || section.getSectionNode() != node
                    || section.getSectionMesh() != CompiledSectionMesh.UNCOMPILED
                    || dirty == null || !dirty.isDirty() || !level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                iterator.remove();
            } else if (distance < best && tracker.hasAllNeighbors(level, node)) {
                best = distance;
                nearest = node;
            }
        }
        if (best == Double.POSITIVE_INFINITY) return;
        var dirty = tracker.getDirtyState(nearest);
        var region = new RenderRegionCache().createRegion(level, nearest);
        frame.sectionUpdateRenderStates.add(new SectionUpdateRenderState(nearest, dirty.isDirtyFromPlayer(), region));
        dirty.setNotDirty();
        pending.remove(nearest);
    }
}
