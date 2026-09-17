package dev.totem.lumen.integration;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.scene.DynamicEntityBroadPhase;
import dev.totem.lumen.scene.DynamicEntitySnapshot;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Render-thread cache for P17 entity geometry snapshots.
 *
 * <p>Minecraft may create a fresh render-state object for the same entity on later frames. P17
 * therefore binds each temporary render state to a stable Totem Lumen instance keyed by
 * {@code (dimension, Entity.getId())}. The retained scene payload remains Minecraft-object-free.
 * Entries not observed for a small number of level ticks are pruned so despawned/cull-hidden
 * entities cannot accumulate indefinitely.</p>
 */
public final class EntityRenderGeometryCache {
    private static final long STALE_TICKS = 3L;

    private static final WeakHashMap<EntityRenderState, Long> STATE_INSTANCE_IDS = new WeakHashMap<>();
    private static final Map<EntityKey, Long> STABLE_INSTANCE_IDS = new HashMap<>();
    private static final Map<Long, EntityKey> INSTANCE_KEYS = new HashMap<>();
    private static final Map<Long, CacheEntry> ENTRIES = new LinkedHashMap<>();
    private static long nextInstanceId = 1L;
    private static long revision;
    private static boolean firstCaptureLogged;
    private static boolean fallbackIdentityLogged;

    private EntityRenderGeometryCache() {
    }

    public static synchronized void bind(Entity entity, EntityRenderState state) {
        if (entity == null || state == null) return;
        String dimensionId = entity.level().dimension().identifier().toString();
        EntityKey key = new EntityKey(dimensionId, entity.getId());
        Long instanceId = STABLE_INSTANCE_IDS.get(key);
        if (instanceId == null) {
            instanceId = allocateInstanceId();
            STABLE_INSTANCE_IDS.put(key, instanceId);
            INSTANCE_KEYS.put(instanceId, key);
        }
        STATE_INSTANCE_IDS.put(state, instanceId);
    }

    public static synchronized long instanceId(EntityRenderState state) {
        Long existing = STATE_INSTANCE_IDS.get(state);
        if (existing != null) return existing;

        // This should only be a compatibility fallback if Minecraft changes the state extraction
        // path without also changing the later dispatcher submission descriptor.
        long id = allocateInstanceId();
        STATE_INSTANCE_IDS.put(state, id);
        if (!fallbackIdentityLogged) {
            fallbackIdentityLogged = true;
            TotemLumenClient.LOGGER.warn(
                    "P17 captured an entity render state before stable entity-id binding; using a temporary instance id"
            );
        }
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
        Set<Long> removedIds = new HashSet<>();
        ENTRIES.entrySet().removeIf(entry -> {
            CacheEntry cached = entry.getValue();
            boolean remove = !dimensionId.equals(cached.snapshot.dimensionId())
                    || levelGameTime - cached.lastSeenGameTime > STALE_TICKS;
            if (remove) removedIds.add(entry.getKey());
            return remove;
        });

        if (!removedIds.isEmpty()) {
            for (long instanceId : removedIds) {
                EntityKey key = INSTANCE_KEYS.remove(instanceId);
                if (key != null) STABLE_INSTANCE_IDS.remove(key);
            }
            STATE_INSTANCE_IDS.entrySet().removeIf(entry -> removedIds.contains(entry.getValue()));
            revision++;
        }
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
        STATE_INSTANCE_IDS.clear();
        STABLE_INSTANCE_IDS.clear();
        INSTANCE_KEYS.clear();
        nextInstanceId = 1L;
        firstCaptureLogged = false;
        fallbackIdentityLogged = false;
    }

    private static long allocateInstanceId() {
        if (nextInstanceId == Long.MAX_VALUE) {
            throw new IllegalStateException("P17 dynamic entity instance id space exhausted");
        }
        return nextInstanceId++;
    }

    private record EntityKey(String dimensionId, int entityId) {
    }

    private record CacheEntry(DynamicEntitySnapshot snapshot, long lastSeenGameTime) {
    }
}
