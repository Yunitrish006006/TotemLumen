package dev.totem.lumen.integration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.material.PbrTextureRuntimeProperties;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/**
 * Client resource-pack material overrides for P18 textures.
 *
 * <p>The shader ABI is intentionally independent from this table. Updating this JSON or the Java
 * loader changes descriptor payloads only and does not alter generated GLSL.</p>
 */
public final class PbrTextureMaterialRuleRegistry {
    private static final Identifier RULES_RESOURCE =
            Identifier.fromNamespaceAndPath("totem-lumen", "material_rules.json");

    private static volatile Map<String, PbrTextureRuntimeProperties> rules = Map.of();
    private static volatile boolean loaded;
    private static volatile long revision;

    private PbrTextureMaterialRuleRegistry() {
    }

    public static void ensureLoaded(ResourceManager resources) {
        if (loaded) return;
        synchronized (PbrTextureMaterialRuleRegistry.class) {
            if (loaded) return;
            rules = loadRules(resources);
            loaded = true;
            revision++;
            TotemLumenClient.LOGGER.info(
                    "P18 material rule table loaded: rules={}, revision={}",
                    rules.size(),
                    revision
            );
        }
    }

    public static PbrTextureRuntimeProperties propertiesFor(String spriteId) {
        PbrTextureRuntimeProperties properties = rules.get(spriteId);
        return properties == null ? PbrTextureRuntimeProperties.DEFAULT : properties;
    }

    public static synchronized void invalidate() {
        rules = Map.of();
        loaded = false;
        revision++;
    }

    public static long revision() {
        return revision;
    }

    private static Map<String, PbrTextureRuntimeProperties> loadRules(ResourceManager resources) {
        try {
            var resource = resources.getResource(RULES_RESOURCE);
            if (resource.isEmpty()) return Map.of();

            try (Reader reader = resource.get().openAsReader()) {
                JsonElement root = JsonParser.parseReader(reader);
                if (!root.isJsonObject()) {
                    throw new IllegalArgumentException("material_rules.json root must be an object");
                }

                JsonObject rootObject = root.getAsJsonObject();
                JsonElement texturesElement = rootObject.get("textures");
                if (texturesElement == null || !texturesElement.isJsonObject()) {
                    return Map.of();
                }

                Map<String, PbrTextureRuntimeProperties> parsed = new HashMap<>();
                for (Map.Entry<String, JsonElement> entry
                        : texturesElement.getAsJsonObject().entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        TotemLumenClient.LOGGER.warn(
                                "Ignoring non-object P18 material rule for {}",
                                entry.getKey()
                        );
                        continue;
                    }
                    parsed.put(entry.getKey(), parseProperties(entry.getValue().getAsJsonObject()));
                }
                return Map.copyOf(parsed);
            }
        } catch (Throwable failure) {
            TotemLumenClient.LOGGER.warn(
                    "Failed to load P18 material rule table {}; using default material behavior",
                    RULES_RESOURCE,
                    failure
            );
            return Map.of();
        }
    }

    private static PbrTextureRuntimeProperties parseProperties(JsonObject object) {
        return new PbrTextureRuntimeProperties(
                readFloat(object, "baseline_emission_scale", 1.0f),
                readFloat(object, "labpbr_emission_scale", 1.0f),
                readFloat(object, "roughness_scale", 1.0f),
                readFloat(object, "normal_strength", 1.0f),
                readFloat(object, "alpha_cutoff", 0.0f)
        );
    }

    private static float readFloat(JsonObject object, String key, float fallback) {
        JsonElement element = object.get(key);
        if (element == null) return fallback;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(key + " must be numeric");
        }
        return element.getAsFloat();
    }
}
