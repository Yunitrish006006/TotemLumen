package dev.totem.lumen.gameplay.light;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.totem.lumen.TotemLumen;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.StrictJsonParser;

import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads optional per-dimension gameplay ambient-light rules from server data packs. */
public final class DimensionLightingRulesReloadListener extends SimpleReloadListener<DimensionLightingRuleSet> {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(TotemLumen.MOD_ID, "dimension_lighting_rules");
    private static final FileToIdConverter CONVERTER = FileToIdConverter.json("totem_lumen/dimension_lighting");
    private static volatile DimensionLightingRuleSet currentRules = DimensionLightingRuleSet.EMPTY;

    @Override
    protected DimensionLightingRuleSet prepare(SharedState state) {
        Map<Identifier, DimensionLightingRule> loaded = new LinkedHashMap<>();
        for (Map.Entry<Identifier, Resource> entry : CONVERTER
                .listMatchingResources(state.resourceManager())
                .entrySet()) {
            Identifier resourceId = entry.getKey();
            Identifier dimensionId = CONVERTER.fileToId(resourceId);
            try (Reader reader = entry.getValue().openAsReader()) {
                loaded.put(dimensionId, parse(dimensionId, StrictJsonParser.parse(reader)));
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to read dimension lighting rule " + resourceId, exception);
            } catch (JsonParseException | IllegalArgumentException exception) {
                throw new JsonParseException(
                        "Invalid Totem Lumen dimension lighting rule " + resourceId + ": " + exception.getMessage(),
                        exception
                );
            }
        }
        return DimensionLightingRuleSet.of(loaded);
    }

    @Override
    protected void apply(DimensionLightingRuleSet prepared, SharedState state) {
        currentRules = prepared;
        TotemLumen.LOGGER.info("Loaded {} server dimension-lighting rule(s)", prepared.size());
    }

    public static DimensionLightingRuleSet currentRules() {
        return currentRules;
    }

    public static void reset() {
        currentRules = DimensionLightingRuleSet.EMPTY;
    }

    private static DimensionLightingRule parse(Identifier dimensionId, JsonElement root) {
        if (!root.isJsonObject()) {
            throw new JsonParseException("root must be a JSON object");
        }
        JsonObject object = root.getAsJsonObject();
        JsonElement colorElement = object.get("environment_color");
        if (colorElement == null || !colorElement.isJsonArray()) {
            throw new JsonParseException("environment_color must be an array of exactly three numbers");
        }
        JsonArray color = colorElement.getAsJsonArray();
        if (color.size() != 3) {
            throw new JsonParseException("environment_color must contain exactly three numbers");
        }
        int strength = readStrength(object.get("gameplay_strength"));
        boolean affectsSpawning = object.has("affects_spawning") && object.get("affects_spawning").getAsBoolean();
        try {
            return new DimensionLightingRule(
                    new EmissionColor(
                            color.get(0).getAsFloat(),
                            color.get(1).getAsFloat(),
                            color.get(2).getAsFloat()
                    ),
                    strength,
                    affectsSpawning
            );
        } catch (RuntimeException exception) {
            throw new JsonParseException("dimension rule " + dimensionId + ": " + exception.getMessage(), exception);
        }
    }

    private static int readStrength(JsonElement element) {
        if (element == null) {
            return 0;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException("gameplay_strength must be an integer from 0 through 15");
        }
        double numeric = element.getAsDouble();
        int value = element.getAsInt();
        if (!Double.isFinite(numeric) || numeric != value || value < 0 || value > 15) {
            throw new JsonParseException("gameplay_strength must be an integer from 0 through 15");
        }
        return value;
    }
}
