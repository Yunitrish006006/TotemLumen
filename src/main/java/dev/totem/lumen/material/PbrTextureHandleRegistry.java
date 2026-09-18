package dev.totem.lumen.material;

import java.util.HashMap;
import java.util.Map;

/**
 * Stable process-lifetime texture handles for renderer-resolved sprite ids.
 *
 * <p>Handles intentionally survive resource-pack reloads. Reload replaces the pixel payload behind
 * a handle without invalidating already captured P14 mesh surface records.</p>
 */
public final class PbrTextureHandleRegistry {
    public static final int MAX_HANDLE = 0x0FFF;

    private static final Map<String, Integer> HANDLES = new HashMap<>();
    private static final Map<Integer, String> SPRITES = new HashMap<>();
    private static int nextHandle = 1;

    private PbrTextureHandleRegistry() {
    }

    public static synchronized int handleFor(String spriteId) {
        if (spriteId == null || spriteId.isBlank()) return 0;
        Integer existing = HANDLES.get(spriteId);
        if (existing != null) return existing;
        if (nextHandle > MAX_HANDLE) return -1;

        int handle = nextHandle++;
        HANDLES.put(spriteId, handle);
        SPRITES.put(handle, spriteId);
        return handle;
    }

    public static synchronized int existingHandle(String spriteId) {
        Integer handle = HANDLES.get(spriteId);
        return handle == null ? 0 : handle;
    }

    public static synchronized String spriteId(int handle) {
        return SPRITES.get(handle);
    }

    public static synchronized int size() {
        return HANDLES.size();
    }
}
