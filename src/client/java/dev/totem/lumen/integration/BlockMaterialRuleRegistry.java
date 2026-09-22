package dev.totem.lumen.integration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.totem.lumen.TotemLumenClient;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Resource-pack rules for block-level renderer material properties.
 *
 * <p>Rules are evaluated in file order and merged, so broad prefix/contains rules can be refined by
 * later exact matches. These rules affect CPU material/light records only and never generated GLSL.</p>
 */
public final class BlockMaterialRuleRegistry {
    private static final Identifier RESOURCE =
            Identifier.fromNamespaceAndPath("totem-lumen", "block_material_rules.json");

    public record Overrides(
            Float emissionR,
            Float emissionG,
            Float emissionB,
            Float lightRadiusScale,
            Float lightIntensityScale,
            Float roughness,
            Float metallic,
            Float opacity,
            Float ior,
            Float transmissionR,
            Float transmissionG,
            Float transmissionB,
            Float reflectionScale
    ) {
        public static final Overrides EMPTY = new Overrides(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null
        );

        Overrides merge(Overrides newer) {
            return new Overrides(
                    choose(newer.emissionR, emissionR),
                    choose(newer.emissionG, emissionG),
                    choose(newer.emissionB, emissionB),
                    choose(newer.lightRadiusScale, lightRadiusScale),
                    choose(newer.lightIntensityScale, lightIntensityScale),
                    choose(newer.roughness, roughness),
                    choose(newer.metallic, metallic),
                    choose(newer.opacity, opacity),
                    choose(newer.ior, ior),
                    choose(newer.transmissionR, transmissionR),
                    choose(newer.transmissionG, transmissionG),
                    choose(newer.transmissionB, transmissionB),
                    choose(newer.reflectionScale, reflectionScale)
            );
        }

        private static Float choose(Float newer, Float older) {
            return newer == null ? older : newer;
        }
    }

    private record Matcher(String exact, String prefix, String suffix, String contains) {
        boolean matches(String sourceId) {
            if (exact != null && !sourceId.equals(exact)) return false;
            if (prefix != null && !sourceId.startsWith(prefix)) return false;
            if (suffix != null && !sourceId.endsWith(suffix)) return false;
            return contains == null || sourceId.contains(contains);
        }
    }

    private record Rule(Matcher matcher, Overrides overrides) {
    }

    private static volatile List<Rule> rules = List.of();
    private static volatile boolean loaded;
    private static volatile long revision;

    private BlockMaterialRuleRegistry() {
    }

    public static void ensureLoaded(ResourceManager resources) {
        if (loaded) return;
        synchronized (BlockMaterialRuleRegistry.class) {
            if (loaded) return;
            rules = load(resources);
            loaded = true;
            revision++;
            TotemLumenClient.LOGGER.info(
                    "Block material rule table loaded: rules={}, revision={}",
                    rules.size(),
                    revision
            );
        }
    }

    public static Overrides overridesFor(String sourceId) {
        Overrides resolved = Overrides.EMPTY;
        for (Rule rule : rules) {
            if (rule.matcher().matches(sourceId)) {
                resolved = resolved.merge(rule.overrides());
            }
        }
        return resolved;
    }

    public static long revision() {
        return revision;
    }

    public static synchronized void invalidate() {
        rules = List.of();
        loaded = false;
        revision++;
    }

    private static List<Rule> load(ResourceManager resources) {
        try {
            var resource = resources.getResource(RESOURCE);
            if (resource.isEmpty()) return List.of();
            try (Reader reader = resource.get().openAsReader()) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) {
                    throw new IllegalArgumentException("block_material_rules.json root must be an object");
                }
                JsonElement rulesElement = parsed.getAsJsonObject().get("rules");
                if (rulesElement == null || !rulesElement.isJsonArray()) return List.of();

                List<Rule> result = new ArrayList<>();
                for (JsonElement element : rulesElement.getAsJsonArray()) {
                    if (!element.isJsonObject()) continue;
                    JsonObject object = element.getAsJsonObject();
                    JsonObject match = requireObject(object, "match");
                    Matcher matcher = new Matcher(
                            stringOrNull(match, "exact"),
                            stringOrNull(match, "prefix"),
                            stringOrNull(match, "suffix"),
                            stringOrNull(match, "contains")
                    );
                    if (matcher.exact() == null
                            && matcher.prefix() == null
                            && matcher.suffix() == null
                            && matcher.contains() == null) {
                        throw new IllegalArgumentException("block material rule requires a matcher");
                    }

                    float[] emission = vec3OrNull(object, "emission_color");
                    float[] transmission = vec3OrNull(object, "transmission_color");
                    Overrides overrides = new Overrides(
                            component(emission, 0),
                            component(emission, 1),
                            component(emission, 2),
                            floatOrNull(object, "light_radius_scale"),
                            floatOrNull(object, "light_intensity_scale"),
                            floatOrNull(object, "roughness"),
                            floatOrNull(object, "metallic"),
                            floatOrNull(object, "opacity"),
                            floatOrNull(object, "ior"),
                            component(transmission, 0),
                            component(transmission, 1),
                            component(transmission, 2),
                            floatOrNull(object, "reflection_scale")
                    );
                    validate(overrides);
                    result.add(new Rule(matcher, overrides));
                }
                return List.copyOf(result);
            }
        } catch (Throwable failure) {
            TotemLumenClient.LOGGER.warn(
                    "Failed to load {}; using Minecraft/material fallbacks",
                    RESOURCE,
                    failure
            );
            return List.of();
        }
    }

    private static void validate(Overrides value) {
        normalized(value.emissionR, "emission_color.r");
        normalized(value.emissionG, "emission_color.g");
        normalized(value.emissionB, "emission_color.b");
        ranged(value.lightRadiusScale, 0.0f, 8.0f, "light_radius_scale");
        ranged(value.lightIntensityScale, 0.0f, 8.0f, "light_intensity_scale");
        normalized(value.roughness, "roughness");
        normalized(value.metallic, "metallic");
        normalized(value.opacity, "opacity");
        ranged(value.ior, 1.0f, 4.0f, "ior");
        normalized(value.transmissionR, "transmission_color.r");
        normalized(value.transmissionG, "transmission_color.g");
        normalized(value.transmissionB, "transmission_color.b");
        ranged(value.reflectionScale, 0.0f, 4.0f, "reflection_scale");
    }

    private static void normalized(Float value, String name) {
        ranged(value, 0.0f, 1.0f, name);
    }

    private static void ranged(Float value, float min, float max, String name) {
        if (value == null) return;
        if (!Float.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(name + " must be finite and in [" + min + ", " + max + "]");
        }
    }

    private static JsonObject requireObject(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(key + " must be an object");
        }
        return element.getAsJsonObject();
    }

    private static String stringOrNull(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null ? null : element.getAsString();
    }

    private static Float floatOrNull(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null ? null : element.getAsFloat();
    }

    private static float[] vec3OrNull(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null) return null;
        if (!element.isJsonArray() || element.getAsJsonArray().size() != 3) {
            throw new IllegalArgumentException(key + " must be an array of three numbers");
        }
        return new float[]{
                element.getAsJsonArray().get(0).getAsFloat(),
                element.getAsJsonArray().get(1).getAsFloat(),
                element.getAsJsonArray().get(2).getAsFloat()
        };
    }

    private static Float component(float[] value, int index) {
        return value == null ? null : value[index];
    }
}
