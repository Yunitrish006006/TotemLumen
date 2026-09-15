package dev.totem.lumen.world;

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

/**
 * Loads world-local lighting rules from server data packs.
 *
 * <p>A resource at {@code data/minecraft/totem_lumen/lighting/torch.json} controls
 * {@code minecraft:torch}. Normal resource-pack/data-pack stacking therefore determines which file
 * wins when multiple packs define the same block.</p>
 */
public final class LightingWorldRulesReloadListener extends SimpleReloadListener<LightingWorldRuleSet> {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(
            TotemLumen.MOD_ID,
            "lighting_world_rules"
    );

    private static final FileToIdConverter CONVERTER = FileToIdConverter.json("totem_lumen/lighting");
    private static volatile LightingWorldRuleSet currentRules = LightingWorldRuleSet.EMPTY;

    @Override
    protected LightingWorldRuleSet prepare(SharedState state) {
        Map<Identifier, LightingWorldRule> loaded = new LinkedHashMap<>();

        for (Map.Entry<Identifier, Resource> entry : CONVERTER
                .listMatchingResources(state.resourceManager())
                .entrySet()) {
            Identifier resourceId = entry.getKey();
            Identifier blockId = CONVERTER.fileToId(resourceId);

            try (Reader reader = entry.getValue().openAsReader()) {
                JsonElement root = StrictJsonParser.parse(reader);
                loaded.put(blockId, parseRule(blockId, root));
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Failed to read Totem Lumen lighting world rule " + resourceId,
                        exception
                );
            } catch (JsonParseException | IllegalArgumentException exception) {
                throw new JsonParseException(
                        "Invalid Totem Lumen lighting world rule " + resourceId
                                + " for block " + blockId + ": " + exception.getMessage(),
                        exception
                );
            }
        }

        return LightingWorldRuleSet.of(loaded);
    }

    @Override
    protected void apply(LightingWorldRuleSet prepared, SharedState state) {
        currentRules = prepared;
        TotemLumen.LOGGER.info(
                "Loaded {} server-authoritative lighting world rule(s)",
                prepared.size()
        );
    }

    public static LightingWorldRuleSet currentRules() {
        return currentRules;
    }

    public static void reset() {
        currentRules = LightingWorldRuleSet.EMPTY;
    }

    private static LightingWorldRule parseRule(Identifier blockId, JsonElement root) {
        if (!root.isJsonObject()) {
            throw new JsonParseException("root must be a JSON object");
        }

        JsonObject object = root.getAsJsonObject();
        JsonElement colorElement = object.get("emission_color");
        if (colorElement == null || !colorElement.isJsonArray()) {
            throw new JsonParseException("emission_color must be an array of exactly three numbers");
        }

        JsonArray color = colorElement.getAsJsonArray();
        if (color.size() != 3) {
            throw new JsonParseException("emission_color must contain exactly three numbers");
        }

        try {
            int gameplayStrength = LightingWorldRule.USE_BLOCK_STATE;
            if (object.has("gameplay_strength")) {
                JsonElement strengthElement = object.get("gameplay_strength");
                if (!strengthElement.isJsonPrimitive()
                        || !strengthElement.getAsJsonPrimitive().isNumber()) {
                    throw new JsonParseException("gameplay_strength must be an integer in [0, 15]");
                }
                double rawStrength = strengthElement.getAsDouble();
                if (!Double.isFinite(rawStrength)
                        || rawStrength != Math.rint(rawStrength)
                        || rawStrength < 0.0
                        || rawStrength > 15.0) {
                    throw new JsonParseException("gameplay_strength must be an integer in [0, 15]");
                }
                gameplayStrength = (int) rawStrength;
            }

            return new LightingWorldRule(
                    color.get(0).getAsFloat(),
                    color.get(1).getAsFloat(),
                    color.get(2).getAsFloat(),
                    gameplayStrength
            );
        } catch (RuntimeException exception) {
            throw new JsonParseException(
                    "lighting rule for " + blockId + " must contain valid finite values: "
                            + exception.getMessage(),
                    exception
            );
        }
    }
}
