package dev.totem.lumen.integration;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.material.MaterialRegistry;
import dev.totem.lumen.render.RendererBootstrap;
import dev.totem.lumen.render.RendererState;
import dev.totem.lumen.scene.FrameSnapshot;
import dev.totem.lumen.scene.RayScene;
import dev.totem.lumen.scene.SceneUpdate;
import dev.totem.lumen.scene.SceneUpdateQueue;
import dev.totem.lumen.scene.SectionCoordinates;
import dev.totem.lumen.scene.SectionKey;
import dev.totem.lumen.scene.SectionSnapshot;
import dev.totem.lumen.scene.SectionVoxelData;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minecraft/Fabric extraction boundary. Mutable Minecraft objects are read only inside callbacks;
 * the queue receives Totem Lumen-owned immutable/copy-owned values.
 */
public final class SceneExtractionBridge {
    private static final int MAX_SECTION_SNAPSHOTS_PER_EXTRACTION = 2;

    private static final SceneUpdateQueue UPDATE_QUEUE = new SceneUpdateQueue();
    private static final RayScene SCENE = new RayScene();
    private static final MaterialRegistry MATERIALS = new MaterialRegistry();
    private static final Set<SectionKey> PENDING_SECTION_SNAPSHOTS = new LinkedHashSet<>();
    private static final Object SNAPSHOT_LOCK = new Object();
    private static final AtomicLong EXTRACTION_FRAMES = new AtomicLong();
    private static final AtomicLong SECTION_REVISIONS = new AtomicLong();
    private static final AtomicReference<FrameSnapshot> LATEST_FRAME = new AtomicReference<>();

    private static boolean initialized;
    private static long clientTicks;
    private static long lastReportedDroppedUpdates;

    private SceneExtractionBridge() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;

        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((minecraft, level) -> onLevelChanged(level));
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> onChunkLoaded(level, chunk));
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> onChunkUnloaded(level, chunk));
        LevelExtractionEvents.END_EXTRACTION.register(SceneExtractionBridge::endExtraction);

        TotemLumenClient.LOGGER.info("P1/P2 scene extraction bridge registered");
    }

    public static void tick() {
        if (!sceneTrackingEnabled()) {
            return;
        }

        SCENE.applyPending(UPDATE_QUEUE);
        clientTicks++;

        long dropped = UPDATE_QUEUE.droppedCount();
        if (dropped != lastReportedDroppedUpdates) {
            TotemLumenClient.LOGGER.warn(
                    "Scene update queue dropped {} update(s); pending={}, loadedChunks={}, dirtySections={}",
                    dropped - lastReportedDroppedUpdates,
                    UPDATE_QUEUE.pendingCount(),
                    SCENE.loadedChunkCount(),
                    SCENE.dirtySectionCount()
            );
            lastReportedDroppedUpdates = dropped;
        }

        if (clientTicks % 600 == 0 && SCENE.activeDimension() != null) {
            FrameSnapshot frame = LATEST_FRAME.get();
            TotemLumenClient.LOGGER.debug(
                    "CPU scene: dimension={}, chunks={}, sections={}, dirty={}, materials={}, snapshotBacklog={}, frame={}",
                    SCENE.activeDimension(),
                    SCENE.loadedChunkCount(),
                    SCENE.populatedSectionCount(),
                    SCENE.dirtySectionCount(),
                    MATERIALS.size(),
                    pendingSnapshotCount(),
                    frame == null ? -1 : frame.frameIndex()
            );
        }
    }

    private static void onChunkLoaded(ClientLevel level, LevelChunk chunk) {
        if (!sceneTrackingEnabled()) {
            return;
        }

        String dimensionId = dimensionId(level);
        var pos = chunk.getPos();
        offer(new SceneUpdate.ChunkLoaded(
                UPDATE_QUEUE.nextSequence(), dimensionId, pos.x(), pos.z()
        ));

        LevelChunkSection[] sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            if (!sections[index].hasOnlyAir()) {
                scheduleSnapshot(new SectionKey(
                        dimensionId,
                        pos.x(),
                        chunk.getSectionYFromSectionIndex(index),
                        pos.z()
                ));
            }
        }
    }

    private static void onChunkUnloaded(ClientLevel level, LevelChunk chunk) {
        if (!sceneTrackingEnabled()) {
            return;
        }

        String dimensionId = dimensionId(level);
        var pos = chunk.getPos();
        removePendingSnapshotsForChunk(dimensionId, pos.x(), pos.z());
        offer(new SceneUpdate.ChunkUnloaded(
                UPDATE_QUEUE.nextSequence(), dimensionId, pos.x(), pos.z()
        ));
    }

    /**
     * Called from the LevelExtractor mixin. The position is copied immediately and never retained.
     */
    public static void onBlockChanged(BlockPos pos, int updateFlags) {
        if (!sceneTrackingEnabled()) {
            return;
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        String dimensionId = dimensionId(level);
        offer(new SceneUpdate.BlockChanged(
                UPDATE_QUEUE.nextSequence(),
                dimensionId,
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                updateFlags
        ));
        SectionCoordinates.forDirtyHalo(
                dimensionId,
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                SceneExtractionBridge::scheduleSnapshot
        );
    }

    private static void endExtraction(LevelExtractionContext context) {
        if (!sceneTrackingEnabled()) {
            return;
        }

        captureFrame(context);
        processSectionSnapshots(context);
    }

    private static void captureFrame(LevelExtractionContext context) {
        var camera = context.camera();
        if (!camera.isInitialized()) {
            return;
        }

        var position = camera.position();
        var rotation = camera.rotation();
        long frameIndex = EXTRACTION_FRAMES.incrementAndGet();

        LATEST_FRAME.set(new FrameSnapshot(
                frameIndex,
                dimensionId(context.level()),
                position.x,
                position.y,
                position.z,
                rotation.x(),
                rotation.y(),
                rotation.z(),
                rotation.w(),
                camera.getFov(),
                camera.isDetached()
        ));
    }

    private static void processSectionSnapshots(LevelExtractionContext context) {
        String activeDimension = dimensionId(context.level());
        for (int processed = 0; processed < MAX_SECTION_SNAPSHOTS_PER_EXTRACTION; processed++) {
            SectionKey key = pollScheduledSnapshot();
            if (key == null) {
                return;
            }
            if (!key.dimensionId().equals(activeDimension)) {
                continue;
            }

            LevelChunk chunk = context.level().getChunkSource().getChunk(
                    key.x(), key.z(), ChunkStatus.FULL, false
            );
            if (chunk == null) {
                continue;
            }

            int sectionIndex = chunk.getSectionIndexFromSectionY(key.y());
            LevelChunkSection[] sections = chunk.getSections();
            if (sectionIndex < 0 || sectionIndex >= sections.length) {
                continue;
            }

            LevelChunkSection section = sections[sectionIndex];
            int[] materialIds = new int[SectionVoxelData.VOXEL_COUNT];
            if (!section.hasOnlyAir()) {
                for (int localY = 0; localY < SectionVoxelData.SIZE; localY++) {
                    for (int localZ = 0; localZ < SectionVoxelData.SIZE; localZ++) {
                        for (int localX = 0; localX < SectionVoxelData.SIZE; localX++) {
                            var blockState = section.getBlockState(localX, localY, localZ);
                            int materialId = MATERIALS.idFor(MinecraftMaterialResolver.resolve(blockState));
                            materialIds[SectionVoxelData.index(localX, localY, localZ)] = materialId;
                        }
                    }
                }
            }

            SectionSnapshot snapshot = new SectionSnapshot(
                    key,
                    SECTION_REVISIONS.incrementAndGet(),
                    new SectionVoxelData(materialIds)
            );
            offer(new SceneUpdate.SectionRebuilt(UPDATE_QUEUE.nextSequence(), snapshot));
        }
    }

    private static void onLevelChanged(ClientLevel level) {
        UPDATE_QUEUE.clear();
        LATEST_FRAME.set(null);
        synchronized (SNAPSHOT_LOCK) {
            PENDING_SECTION_SNAPSHOTS.clear();
        }

        if (!sceneTrackingEnabled()) {
            return;
        }

        if (level == null) {
            offer(new SceneUpdate.LevelCleared(UPDATE_QUEUE.nextSequence()));
            TotemLumenClient.LOGGER.info("Totem Lumen scene detached from client level");
            return;
        }

        String dimensionId = dimensionId(level);
        offer(new SceneUpdate.LevelChanged(UPDATE_QUEUE.nextSequence(), dimensionId));
        TotemLumenClient.LOGGER.info("Totem Lumen scene attached to {}", dimensionId);
    }

    private static void scheduleSnapshot(SectionKey key) {
        synchronized (SNAPSHOT_LOCK) {
            PENDING_SECTION_SNAPSHOTS.add(key);
        }
    }

    private static SectionKey pollScheduledSnapshot() {
        synchronized (SNAPSHOT_LOCK) {
            Iterator<SectionKey> iterator = PENDING_SECTION_SNAPSHOTS.iterator();
            if (!iterator.hasNext()) {
                return null;
            }
            SectionKey next = iterator.next();
            iterator.remove();
            return next;
        }
    }

    private static void removePendingSnapshotsForChunk(String dimensionId, int chunkX, int chunkZ) {
        synchronized (SNAPSHOT_LOCK) {
            PENDING_SECTION_SNAPSHOTS.removeIf(key ->
                    key.dimensionId().equals(dimensionId) && key.x() == chunkX && key.z() == chunkZ
            );
        }
    }

    private static int pendingSnapshotCount() {
        synchronized (SNAPSHOT_LOCK) {
            return PENDING_SECTION_SNAPSHOTS.size();
        }
    }

    private static String dimensionId(ClientLevel level) {
        ResourceKey<Level> key = level.dimension();
        return key.identifier().toString();
    }

    private static void offer(SceneUpdate update) {
        if (!UPDATE_QUEUE.offer(update)) {
            TotemLumenClient.LOGGER.warn("Scene update queue is full; dropping {}", update.getClass().getSimpleName());
        }
    }

    private static boolean sceneTrackingEnabled() {
        return RendererBootstrap.state() != RendererState.DISABLED_NON_VULKAN;
    }

    public static RayScene scene() {
        return SCENE;
    }

    public static MaterialRegistry materials() {
        return MATERIALS;
    }

    public static long extractionFrameCount() {
        return EXTRACTION_FRAMES.get();
    }

    public static FrameSnapshot latestFrame() {
        return LATEST_FRAME.get();
    }
}
