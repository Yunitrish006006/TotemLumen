package dev.totem.lumen.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.material.EntityMaterialData;
import dev.totem.lumen.material.PbrImage;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Resource-pack controlled dynamic entity material rules.
 *
 * <p>All entity-specific knowledge lives here. The P17 shader sees only material slots and a stable
 * descriptor ABI, so adding or retuning an emissive entity does not change generated GLSL.</p>
 */
public final class EntityMaterialRuleRegistry {
    private static final Identifier RULES_RESOURCE =
            Identifier.fromNamespaceAndPath("totem-lumen", "entity_material_rules.json");

    private static volatile List<EntityMaterialData> materials = List.of();
    private static volatile boolean loaded;
    private static volatile long revision;

    private EntityMaterialRuleRegistry() {
    }

    public static void ensureLoaded(ResourceManager resources) {
        if (loaded) return;
        synchronized (EntityMaterialRuleRegistry.class) {
            if (loaded) return;
            materials = load(resources);
            loaded = true;
            revision++;
            TotemLumenClient.LOGGER.info(
                    "P17 entity material table loaded: materials={}, albedo={}, emissive={}, revision={}",
                    materials.size(),
                    materials.stream().filter(EntityMaterialData::hasAlbedoTexture).count(),
                    materials.stream().filter(EntityMaterialData::hasEmissiveTexture).count(),
                    revision
            );
        }
    }

    public static List<EntityMaterialData> snapshot() {
        return materials;
    }

    public static long revision() {
        return revision;
    }

    public static synchronized void invalidate() {
        materials = List.of();
        loaded = false;
        revision++;
    }

    private static List<EntityMaterialData> load(ResourceManager resources) {
        try {
            var resource = resources.getResource(RULES_RESOURCE);
            if (resource.isEmpty()) return List.of();

            try (Reader reader = resource.get().openAsReader()) {
                JsonElement root = JsonParser.parseReader(reader);
                if (!root.isJsonObject()) {
                    throw new IllegalArgumentException(
                            "entity_material_rules.json root must be an object"
                    );
                }

                JsonElement materialsElement = root.getAsJsonObject().get("materials");
                if (materialsElement == null || !materialsElement.isJsonObject()) {
                    return List.of();
                }

                List<EntityMaterialData> parsed = new ArrayList<>();
                for (var entry : materialsElement.getAsJsonObject().entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        TotemLumenClient.LOGGER.warn(
                                "Ignoring non-object entity material rule for {}",
                                entry.getKey()
                        );
                        continue;
                    }
                    JsonObject object = entry.getValue().getAsJsonObject();
                    float emissiveGain = readFloat(object, "emissive_gain", 0.0f);
                    float alphaCutoff = readFloat(object, "alpha_cutoff", 0.0f);
                    float roughness = readFloat(object, "roughness", 0.8f);
                    float metallic = readFloat(object, "metallic", 0.0f);
                    float reflectionScale = readFloat(object, "reflection_scale", 1.0f);
                    PbrImage albedo = loadFirstTexture(
                            resources,
                            textureCandidates(object, "albedo_textures")
                    );
                    PbrImage emissive = loadFirstTexture(
                            resources,
                            textureCandidates(object, "emissive_textures")
                    );
                    parsed.add(new EntityMaterialData(
                            entry.getKey(),
                            albedo,
                            emissive,
                            emissiveGain,
                            alphaCutoff,
                            roughness,
                            metallic,
                            reflectionScale
                    ));
                }
                parsed.sort(Comparator.comparing(EntityMaterialData::entityTypeId));
                return List.copyOf(parsed);
            }
        } catch (Throwable failure) {
            TotemLumenClient.LOGGER.warn(
                    "Failed to load {}; dynamic entities use baseline material only",
                    RULES_RESOURCE,
                    failure
            );
            return List.of();
        }
    }

    private static List<Identifier> textureCandidates(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null) return List.of();

        List<Identifier> result = new ArrayList<>();
        if (element.isJsonPrimitive()) {
            Identifier parsed = parseIdentifier(element.getAsString());
            if (parsed != null) result.add(parsed);
            return List.copyOf(result);
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException(key + " must be a string or array of strings");
        }

        JsonArray array = element.getAsJsonArray();
        for (JsonElement candidate : array) {
            if (!candidate.isJsonPrimitive()) continue;
            Identifier parsed = parseIdentifier(candidate.getAsString());
            if (parsed != null) result.add(parsed);
        }
        return List.copyOf(result);
    }

    private static Identifier parseIdentifier(String value) {
        if (value == null || value.isBlank()) return null;
        int colon = value.indexOf(':');
        String namespace = colon >= 0 ? value.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? value.substring(colon + 1) : value;
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    private static PbrImage loadFirstTexture(
            ResourceManager resources,
            List<Identifier> candidates
    ) {
        for (Identifier location : candidates) {
            try {
                var resource = resources.getResource(location);
                if (resource.isEmpty()) continue;
                try (InputStream input = resource.get().open();
                     NativeImage image = NativeImage.read(input)) {
                    TotemLumenClient.LOGGER.info(
                            "P17 entity emissive texture resolved: {} ({}x{})",
                            location,
                            image.getWidth(),
                            image.getHeight()
                    );
                    return new PbrImage(
                            image.getWidth(),
                            image.getHeight(),
                            image.getPixels()
                    );
                }
            } catch (Throwable failure) {
                TotemLumenClient.LOGGER.warn(
                        "Failed to decode entity emissive texture {}; trying next candidate",
                        location,
                        failure
                );
            }
        }
        return null;
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
