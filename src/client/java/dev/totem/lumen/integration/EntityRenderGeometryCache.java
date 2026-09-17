package dev.totem.lumen.integration;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.scene.DynamicEntityBroadPhase;
import dev.totem.lumen.scene.DynamicEntitySnapshot;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Render-thread cache for P17 entity geometry snapshots.
 *
 * <p>Render-state objects are only used as weak identity anchors while capture is active. The
 * retained scene payload is Minecraft-object-free and can later be uploaded to the Vulkan scene.
 * Entries not observed for a small number of level ticks are pruned so despawned/cull-hidden
 * entities cannot accumulate indefinitely.</p>
 */
public final class EntityRenderGeometryCache {
    private static final long STALE_TICKS = 3L;

    private static final WeakHashMap<EntityRenderState, Long> INSTANCE_IDS = new WeakHashMap<>();
    private static final Map<Long, CacheEntry> ENTRIES = new LinkedHashMap<>();
    private static long nextInstanceId = 1L;
    private static long revision;
    private static boolean firstCaptureLogged;

    private EntityRenderGeometryCache() {
    }

    public static synchronized long instanceId(EntityRenderState state) {
        Long existing = INSTANCE_IDS.get(state);
        if (existing != null) return existing;
        long id = nextInstanceId++;
        INSTANCE_IDS.put(state, id);
        return id;
    }

    public static synchronized void publish(
            EntityRenderState state,
            DynamicEntitySnapshot snapshot,
            long levelGameTime
    ) {
        if (state == null || snapshot == null) return;
        CacheEntry previous = ENTRIES.put(
                snapshot.instanceId(),
                new CacheEntry(snapshot, levelGameTime)
        );
        if (previous == null || !previous.snapshot.geometryEquals(snapshot)) {
            revision++;
        }

        if (!firstCaptureLogged) {
            firstCaptureLogged = true;
            TotemLumenClient.LOGGER.info(
                    "P17 dynamic entity geometry capture active: type={} quads={}",
                    snapshot.entityTypeId(),
                    snapshot.quadCount()
            );
        }
    }

    public static synchronized void prune(String dimensionId, long levelGameTime) {
        if (dimensionId == null) return;
        boolean changed = ENTRIES.entrySet().removeIf(entry -> {
            CacheEntry cached = entry.getValue();
            return !dimensionId.equals(cached.snapshot.dimensionId())
                    || levelGameTime - cached.lastSeenGameTime > STALE_TICKS;
        });
        if (changed) revision++;
    }

    public static synchronized List<DynamicEntitySnapshot> snapshot() {
        List<DynamicEntitySnapshot> result = new ArrayList<>(ENTRIES.size());
        for (CacheEntry entry : ENTRIES.values()) {
            result.add(entry.snapshot);
        }
        return List.copyOf(result);
    }

    public static synchronized DynamicEntityBroadPhase broadPhase() {
        return DynamicEntityBroadPhase.build(snapshot());
    }

    public static synchronized long revision() {
        return revision;
    }

    public static synchronized int size() {
        return ENTRIES.size();
    }

    public static synchronized void clear() {
        if (!ENTRIES.isEmpty()) revision++;
        ENTRIES.clear();
        INSTANCE_IDS.clear();
        nextInstanceId = 1L;
        firstCaptureLogged = false;
    }

    private record CacheEntry(DynamicEntitySnapshot snapshot, long lastSeenGameTime) {
    }
}
