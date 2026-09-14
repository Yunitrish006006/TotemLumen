package dev.totem.lumen.integration;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.render.RendererBootstrap;
import dev.totem.lumen.render.RendererState;
import dev.totem.lumen.scene.FrameSnapshot;
import dev.totem.lumen.scene.RayScene;
import dev.totem.lumen.scene.SceneUpdate;
import dev.totem.lumen.scene.SceneUpdateQueue;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * P1 boundary between Minecraft/Fabric lifecycle callbacks and Totem Lumen-owned scene state.
 */
public final class SceneExtractionBridge {
    private static final SceneUpdateQueue UPDATE_QUEUE = new SceneUpdateQueue();
    private static final RayScene SCENE = new RayScene();
    private static final AtomicLong EXTRACTION_FRAMES = new AtomicLong();
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
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            if (!sceneTrackingEnabled()) {
                return;
            }
            String dimensionId = dimensionId(level);
            var pos = chunk.getPos();
            offer(new SceneUpdate.ChunkLoaded(
                    UPDATE_QUEUE.nextSequence(), dimensionId, pos.x(), pos.z()
            ));
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            if (!sceneTrackingEnabled()) {
                return;
            }
            String dimensionId = dimensionId(level);
            var pos = chunk.getPos();
            offer(new SceneUpdate.ChunkUnloaded(
                    UPDATE_QUEUE.nextSequence(), dimensionId, pos.x(), pos.z()
            ));
        });

        LevelExtractionEvents.END_EXTRACTION.register(SceneExtractionBridge::captureFrame);

        TotemLumenClient.LOGGER.info("P1 scene extraction bridge registered");
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
            if (frame != null) {
                TotemLumenClient.LOGGER.debug(
                        "P1 scene: dimension={}, chunks={}, dirtySections={}, blockChanges={}, frame={}, camera=({}, {}, {}), fov={}, queuePending={}",
                        SCENE.activeDimension(),
                        SCENE.loadedChunkCount(),
                        SCENE.dirtySectionCount(),
                        SCENE.blockChangeCount(),
                        frame.frameIndex(),
                        frame.cameraX(),
                        frame.cameraY(),
                        frame.cameraZ(),
                        frame.fovDegrees(),
                        UPDATE_QUEUE.pendingCount()
                );
            }
        }
    }

    /**
     * Called only from the LevelExtractor mixin. Copy the position immediately and never retain it.
     */
    public static void onBlockChanged(BlockPos pos, int updateFlags) {
        if (!sceneTrackingEnabled()) {
            return;
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        offer(new SceneUpdate.BlockChanged(
                UPDATE_QUEUE.nextSequence(),
                dimensionId(level),
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                updateFlags
        ));
    }

    private static void captureFrame(LevelExtractionContext context) {
        if (!sceneTrackingEnabled()) {
            return;
        }

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

    private static void onLevelChanged(ClientLevel level) {
        UPDATE_QUEUE.clear();
        LATEST_FRAME.set(null);

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

    private static String dimensionId(ClientLevel level) {
        ResourceKey<Level> key = level.dimension();
        return key.identifier().toString();
    }

    private static void offer(SceneUpdate update) {
        if (!UPDATE_QUEUE.offer(update)) {
            TotemLumenClient.LOGGER.warn("Scene update queue is full; dropping {}", update.getClass().getSimpleName());
        }
    }

    /**
     * Scene extraction itself is backend-neutral and may safely run while the graphics device is
     * still being probed. Once a non-Vulkan backend is confirmed, all scene tracking stops.
     */
    private static boolean sceneTrackingEnabled() {
        return RendererBootstrap.state() != RendererState.DISABLED_NON_VULKAN;
    }

    public static RayScene scene() {
        return SCENE;
    }

    public static long extractionFrameCount() {
        return EXTRACTION_FRAMES.get();
    }

    public static FrameSnapshot latestFrame() {
        return LATEST_FRAME.get();
    }
}
