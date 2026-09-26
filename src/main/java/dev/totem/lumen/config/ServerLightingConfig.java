package dev.totem.lumen.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.totem.lumen.gameplay.light.EmissionColor;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Immutable server-only operator settings; world data packs remain the source of per-world rules. */
public record ServerLightingConfig(SpawnMode spawnMode, int workBudget, long timeBudgetNanos,
                                   boolean heldLightEnabled, Map<Identifier, BlockOverride> blockOverrides) {
    public static final String DEFAULT_JSON = """
            {
              "schema_version": 1,
              "gameplay": {
                "spawn_light_mode": "RGB",
                "work_budget_per_tick": 20000,
                "time_budget_ms": 2.0
              },
              "held_light": { "enabled": true },
              "block_overrides": {}
            }
            """;
    public static final ServerLightingConfig DEFAULT = new ServerLightingConfig(
            SpawnMode.RGB, 20_000, 2_000_000L, true, Map.of());

    public enum SpawnMode { RGB, VANILLA }

    public record BlockOverride(EmissionColor color, Integer gameplayStrength) {
        public BlockOverride {
            if (color == null && gameplayStrength == null) {
                throw new IllegalArgumentException("block override must specify color or strength");
            }
            if (gameplayStrength != null && (gameplayStrength < 0 || gameplayStrength > 15)) {
                throw new IllegalArgumentException("gameplay_strength must be in [0, 15]");
            }
        }
    }

    public ServerLightingConfig {
        if (spawnMode == null || workBudget < 1_000 || workBudget > 100_000
                || timeBudgetNanos < 250_000L || timeBudgetNanos > 5_000_000L
                || blockOverrides == null || blockOverrides.size() > 8_192) {
            throw new IllegalArgumentException("invalid server lighting settings");
        }
        blockOverrides = Map.copyOf(blockOverrides);
    }

    public static ServerLightingConfig parse(String json) {
        if (json == null || json.length() > 262_144) {
            throw new IllegalArgumentException("server lighting config exceeds 256 KiB");
        }
        rejectDuplicateKeys(json);
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("config root must be an object");
        JsonObject root = parsed.getAsJsonObject();
        onlyKeys(root, Set.of("schema_version", "gameplay", "held_light", "block_overrides"), "root");
        if (integer(root, "schema_version") != 1) {
            throw new IllegalArgumentException("unsupported schema_version");
        }
        JsonObject gameplay = object(root, "gameplay");
        onlyKeys(gameplay, Set.of("spawn_light_mode", "work_budget_per_tick", "time_budget_ms"), "gameplay");
        SpawnMode mode = SpawnMode.valueOf(string(gameplay, "spawn_light_mode"));
        int work = integer(gameplay, "work_budget_per_tick");
        double milliseconds = number(gameplay, "time_budget_ms");
        if (!Double.isFinite(milliseconds) || milliseconds < 0.25 || milliseconds > 5.0) {
            throw new IllegalArgumentException("time_budget_ms must be in [0.25, 5.0]");
        }
        JsonObject held = object(root, "held_light");
        onlyKeys(held, Set.of("enabled"), "held_light");
        JsonElement enabled = held.get("enabled");
        if (enabled == null || !enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("held_light.enabled must be boolean");
        }

        Map<Identifier, BlockOverride> overrides = new LinkedHashMap<>();
        JsonObject blockRules = object(root, "block_overrides");
        if (blockRules.size() > 8_192) throw new IllegalArgumentException("too many block_overrides");
        for (Map.Entry<String, JsonElement> entry : blockRules.entrySet()) {
            Identifier id = Identifier.parse(entry.getKey());
            if (!entry.getValue().isJsonObject()) {
                throw new IllegalArgumentException("block_overrides." + id + " must be an object");
            }
            JsonObject rule = entry.getValue().getAsJsonObject();
            onlyKeys(rule, Set.of("emission_color", "gameplay_strength"), "block_overrides." + id);
            EmissionColor color = null;
            if (rule.has("emission_color")) {
                JsonElement colorElement = rule.get("emission_color");
                if (!colorElement.isJsonArray()) {
                    throw new IllegalArgumentException("emission_color for " + id + " must be an array");
                }
                JsonArray array = colorElement.getAsJsonArray();
                if (array.size() != 3) {
                    throw new IllegalArgumentException("emission_color for " + id + " needs 3 values");
                }
                color = new EmissionColor(
                        (float) number(array.get(0), "emission_color.r"),
                        (float) number(array.get(1), "emission_color.g"),
                        (float) number(array.get(2), "emission_color.b"));
            }
            Integer strength = rule.has("gameplay_strength")
                    ? integer(rule, "gameplay_strength") : null;
            overrides.put(id, new BlockOverride(color, strength));
        }
        return new ServerLightingConfig(mode, work, Math.round(milliseconds * 1_000_000.0),
                enabled.getAsBoolean(), overrides);
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(key + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return value.getAsString();
    }

    private static int integer(JsonObject object, String key) {
        double value = number(object.get(key), key);
        if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return (int) value;
    }

    private static double number(JsonObject object, String key) {
        return number(object.get(key), key);
    }

    private static double number(JsonElement element, String key) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(key + " must be a number");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) throw new IllegalArgumentException(key + " must be finite");
        return value;
    }

    private static void onlyKeys(JsonObject object, Set<String> allowed, String location) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) throw new IllegalArgumentException("unknown " + location + "." + key);
        }
    }

    private static void rejectDuplicateKeys(String json) {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            scanJson(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("trailing JSON content");
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("invalid server lighting JSON", failure);
        }
    }

    private static void scanJson(JsonReader reader, int depth) throws IOException {
        if (depth > 12) throw new IllegalArgumentException("server lighting JSON is too deep");
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                Set<String> keys = new HashSet<>();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (!keys.add(key)) throw new IllegalArgumentException("duplicate JSON key: " + key);
                    scanJson(reader, depth + 1);
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                while (reader.hasNext()) scanJson(reader, depth + 1);
                reader.endArray();
            }
            case STRING, NUMBER -> reader.nextString();
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> reader.nextNull();
            default -> throw new IllegalArgumentException("invalid server lighting JSON token");
        }
    }
}
