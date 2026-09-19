package dev.totem.lumen.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.material.EntityMaterialDefinition;
import dev.totem.lumen.material.PbrImage;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.io.Reader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resource-pack controlled dynamic-entity material table.
 *
 * <p>Entity-specific knowledge stays on the CPU. The production P17 shader consumes only generic
 * material descriptor indices and texture handles, so adding another emissive mob does not alter
 * generated GLSL or invalidate SPIR-V / MoltenVK pipeline caches.</p>
 */
public final class EntityMaterialRuleRegistry {
    private static final Identifier RULES_RESOURCE =
            Identifier.fromNamespaceAndPath("totem-lumen", "entity_material_rules.json");

    private static volatile Map<String, EntityMaterialDefinition> definitions = Map.of();
    private static volatile boolean loaded;
    private static volatile long revision;

    private EntityMaterialRuleRegistry() {
    }

    public static void ensureLoaded(ResourceManager resources) {
        if (loaded) return;
        synchronized (EntityMaterialRuleRegistry.class) {
            if (loaded) return;
            definitions = load(resources);
            loaded = true;
            revision++;
            TotemLumenClient.LOGGER.info(
                    "P17 entity material table loaded: materials={}, revision={}",
                    definitions.size(),
                    revision
            );
        }
    }

    public static Map<String, EntityMaterialDefinition> snapshot() {
        return definitions;
    }

    public static long revision() {
        return revision;
    }

    public static synchronized void invalidate() {
        definitions = Map.of();
        loaded = false;
        revision++;
    }

    private static Map<String, EntityMaterialDefinition> load(ResourceManager resources) {
        var ruleResource = resources.getResource(RULES_RESOURCE);
        if (ruleResource.isEmpty()) return Map.of();

        try (Reader reader = ruleResource.get().openAsReader()) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("entity_material_rules.json root must be an object");
            }
            JsonElement materialsElement = parsed.getAsJsonObject().get("materials");
            if (materialsElement == null || !materialsElement.isJsonObject()) {
                return Map.of();
            }

            Map<Identifier, PbrImage> imageCache = new HashMap<>();
            Map<String, EntityMaterialDefinition> result = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry
                    : materialsElement.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject object = entry.getValue().getAsJsonObject();
                float gain = readFloat(object, "emissive_gain", 1.0f);
                PbrImage emissive = loadFirstTexture(
                        resources,
                        textureCandidates(object.get("emissive_texture")),
                        imageCache
                );
                result.put(
                        entry.getKey(),
                        new EntityMaterialDefinition(entry.getKey(), emissive, gain)
                );
            }
            return Map.copyOf(result);
        } catch (Throwable failure) {
            TotemLumenClient.LOGGER.warn(
                    "Failed to load P17 entity material table {}; using non-emissive entity defaults",
                    RULES_RESOURCE,
                    failure
            );
            return Map.of();
        }
    }

    private static List<Identifier> textureCandidates(JsonElement element) {
        if (element == null) return List.of();
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            Identifier parsed = parseIdentifier(element.getAsString());
            return parsed == null ? List.of() : List.of(parsed);
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException("emissive_texture must be a string or string array");
        }

        java.util.ArrayList<Identifier> result = new java.util.ArrayList<>();
        JsonArray array = element.getAsJsonArray();
        for (JsonElement candidate : array) {
            if (!candidate.isJsonPrimitive() || !candidate.getAsJsonPrimitive().isString()) {
                continue;
            }
            Identifier parsed = parseIdentifier(candidate.getAsString());
            if (parsed != null) result.add(parsed);
        }
        return List.copyOf(result);
    }

    private static PbrImage loadFirstTexture(
            ResourceManager resources,
            List<Identifier> candidates,
            Map<Identifier, PbrImage> imageCache
    ) {
        for (Identifier location : candidates) {
            PbrImage cached = imageCache.get(location);
            if (cached != null) return cached;
            try {
                var resource = resources.getResource(location);
                if (resource.isEmpty()) continue;
                try (InputStream input = resource.get().open();
                     NativeImage image = NativeImage.read(input)) {
                    PbrImage decoded = new PbrImage(
                            image.getWidth(),
                            image.getHeight(),
                            image.getPixels()
                    );
                    imageCache.put(location, decoded);
                    return decoded;
                }
            } catch (Throwable failure) {
                TotemLumenClient.LOGGER.warn(
                        "P17 failed to decode entity material texture {}",
                        location,
                        failure
                );
            }
        }
        return null;
    }

    private static Identifier parseIdentifier(String value) {
        int colon = value.indexOf(':');
        if (colon <= 0 || colon == value.length() - 1) return null;
        return Identifier.fromNamespaceAndPath(
                value.substring(0, colon),
                value.substring(colon + 1)
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
