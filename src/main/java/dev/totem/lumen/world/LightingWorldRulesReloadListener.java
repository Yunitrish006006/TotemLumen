package dev.totem.lumen.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.totem.lumen.TotemLumen;
import dev.totem.lumen.config.ServerLightingConfigStore;
import dev.totem.lumen.gameplay.light.EmissionColor;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.StrictJsonParser;
import net.minecraft.world.level.block.Block;

import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;

/**
 * Loads world-local lighting rules from server data packs.
 *
 * <p>A resource at {@code data/minecraft/totem_lumen/lighting/torch.json} controls
 * {@code minecraft:torch}. Normal resource-pack/data-pack stacking therefore determines which file
 * wins when multiple packs define the same block.</p>
 */
public final class LightingWorldRulesReloadListener extends SimpleReloadListener<LightingWorldRulesReloadListener.Prepared> {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(
            TotemLumen.MOD_ID,
            "lighting_world_rules"
    );

    private static final FileToIdConverter CONVERTER = FileToIdConverter.json("totem_lumen/lighting");
    private static final Identifier DEFAULT_PALETTE = Identifier.fromNamespaceAndPath(
            TotemLumen.MOD_ID, "totem_lumen/default_lighting.json");
    private static final Identifier LIGHT_TUNING = Identifier.fromNamespaceAndPath(
            TotemLumen.MOD_ID, "totem_lumen/light_tuning.json");
    private static volatile LightingWorldRuleSet currentRules = LightingWorldRuleSet.EMPTY;
    private static volatile Map<String, LightingWorldRuleSet> currentPackRules = Map.of();
    private static volatile Map<String, LightingTuning> currentPackTuning = Map.of();
    private static volatile Set<Identifier> currentDefaultIds = Set.of();
    private static volatile boolean currentDefaultPackActive;
    private static volatile LightingTuning currentTuning = LightingTuning.DEFAULT;

    record Prepared(LightingWorldRuleSet rules, Map<String, LightingWorldRuleSet> packRules,
                    Map<String, LightingTuning> packTuning, Set<Identifier> defaultIds,
                    boolean defaultPackActive, LightingTuning tuning) {}

    private record Matcher(String exact, String contains, EmissionColor color) {
        boolean matches(String id) {
            return exact != null ? exact.equals(id) : id.contains(contains);
        }
    }

    @Override
    protected Prepared prepare(SharedState state) {
        Map<Identifier, LightingWorldRule> loaded = new LinkedHashMap<>();
        Map<String, Map<Identifier, LightingWorldRule>> byPack = new LinkedHashMap<>();
        Set<Identifier> defaultIds = new HashSet<>();
        LightingTuning tuning = LightingTuning.DEFAULT;
        Map<String, LightingTuning> tuningByPack = new LinkedHashMap<>();
        Resource tuningResource = state.resourceManager().getResource(LIGHT_TUNING).orElse(null);
        if (tuningResource != null) {
            try (Reader reader = tuningResource.openAsReader()) {
                tuning = parseTuning(StrictJsonParser.parse(reader).getAsJsonObject());
            } catch (IOException | RuntimeException exception) {
                throw new JsonParseException("Invalid Totem Lumen light tuning data pack", exception);
            }
        }
        for (Resource layer : state.resourceManager().getResourceStack(LIGHT_TUNING)) {
            try (Reader reader = layer.openAsReader()) {
                tuningByPack.put(layer.sourcePackId(),
                        parseTuning(StrictJsonParser.parse(reader).getAsJsonObject()));
            } catch (IOException | RuntimeException exception) {
                TotemLumen.LOGGER.warn("Ignoring shadowed light tuning in pack {}: {}",
                        layer.sourcePackId(), exception.toString());
            }
        }
        Resource resource = state.resourceManager().getResource(DEFAULT_PALETTE).orElse(null);
        if (resource != null) {
            try (Reader reader = resource.openAsReader()) {
                loaded.putAll(parsePalette(StrictJsonParser.parse(reader).getAsJsonObject()));
                defaultIds.addAll(loaded.keySet());
            } catch (IOException | RuntimeException exception) {
                throw new JsonParseException("Invalid Totem Lumen default lighting data pack", exception);
            }
        }
        for (Resource palette : state.resourceManager().getResourceStack(DEFAULT_PALETTE)) {
            try (Reader reader = palette.openAsReader()) {
                byPack.computeIfAbsent(palette.sourcePackId(), ignored -> new LinkedHashMap<>())
                        .putAll(parsePalette(StrictJsonParser.parse(reader).getAsJsonObject()));
            } catch (IOException | RuntimeException exception) {
                TotemLumen.LOGGER.warn("Ignoring shadowed default palette in pack {}: {}",
                        palette.sourcePackId(), exception.toString());
            }
        }

        for (Map.Entry<Identifier, Resource> entry : CONVERTER
                .listMatchingResources(state.resourceManager())
                .entrySet()) {
            Identifier resourceId = entry.getKey();
            Identifier blockId = CONVERTER.fileToId(resourceId);

            try (Reader reader = entry.getValue().openAsReader()) {
                JsonElement root = StrictJsonParser.parse(reader);
                loaded.put(blockId, parseRule(blockId, root));
                defaultIds.remove(blockId);
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
            for (Resource layer : state.resourceManager().getResourceStack(resourceId)) {
                try (Reader reader = layer.openAsReader()) {
                    byPack.computeIfAbsent(layer.sourcePackId(), ignored -> new LinkedHashMap<>())
                            .put(blockId, parseRule(blockId, StrictJsonParser.parse(reader)));
                } catch (IOException | RuntimeException exception) {
                    TotemLumen.LOGGER.warn("Ignoring shadowed lighting rule {} in pack {}: {}",
                            blockId, layer.sourcePackId(), exception.toString());
                }
            }
        }
        Map<String, LightingWorldRuleSet> snapshots = new LinkedHashMap<>();
        byPack.forEach((id, rules) -> snapshots.put(id, LightingWorldRuleSet.of(rules)));
        return new Prepared(LightingWorldRuleSet.of(loaded), Map.copyOf(snapshots),
                Map.copyOf(tuningByPack), Set.copyOf(defaultIds), resource != null, tuning);
    }

    @Override
    protected void apply(Prepared prepared, SharedState state) {
        EffectiveLightingRules.Snapshot effective = EffectiveLightingRules.compose(
                prepared.rules(), ServerLightingConfigStore.current(), prepared.defaultIds(),
                prepared.defaultPackActive());
        currentRules = prepared.rules();
        currentPackRules = prepared.packRules();
        currentPackTuning = prepared.packTuning();
        currentDefaultIds = prepared.defaultIds();
        currentDefaultPackActive = prepared.defaultPackActive();
        currentTuning = prepared.tuning();
        EffectiveLightingRules.install(effective);
        TotemLumen.LOGGER.info(
                "Loaded {} server-authoritative lighting world rule(s), including {} default data-pack rules",
                prepared.rules().size(), prepared.defaultIds().size()
        );
    }

    public static LightingWorldRuleSet currentRules() {
        return currentRules;
    }

    public static LightingWorldRuleSet rulesForPack(String packId) {
        return currentPackRules.getOrDefault(packId, LightingWorldRuleSet.EMPTY);
    }

    public static java.util.Optional<LightingTuning> tuningForPack(String packId) {
        return java.util.Optional.ofNullable(currentPackTuning.get(packId));
    }

    public static Set<String> currentPackIds() {
        java.util.Set<String> result = new java.util.HashSet<>(currentPackRules.keySet());
        result.addAll(currentPackTuning.keySet());
        return Set.copyOf(result);
    }

    public static Set<Identifier> currentDefaultIds() {
        return currentDefaultIds;
    }

    public static boolean defaultPackActive() {
        return currentDefaultPackActive;
    }

    public static LightingTuning currentTuning() {
        return currentTuning;
    }

    public static void reset() {
        currentRules = LightingWorldRuleSet.EMPTY;
        currentPackRules = Map.of();
        currentPackTuning = Map.of();
        currentDefaultIds = Set.of();
        currentDefaultPackActive = false;
        currentTuning = LightingTuning.DEFAULT;
    }

    private static EmissionColor parseColor(JsonElement element, String field) {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 3) {
            throw new JsonParseException(field + " must be three RGB components");
        }
        JsonArray values = element.getAsJsonArray();
        try {
            return new EmissionColor(values.get(0).getAsFloat(), values.get(1).getAsFloat(),
                    values.get(2).getAsFloat());
        } catch (RuntimeException exception) {
            throw new JsonParseException(field + " must be finite RGB values in [0, 1]", exception);
        }
    }

    private static Map<Identifier, LightingWorldRule> parsePalette(JsonObject palette) {
        EmissionColor fallback = parseColor(palette.get("fallback"), "fallback");
        JsonArray matchersJson = palette.getAsJsonArray("matchers");
        if (matchersJson == null || matchersJson.size() > 256) {
            throw new JsonParseException("matchers must be an array of at most 256 rules");
        }
        List<Matcher> matchers = new ArrayList<>();
        for (JsonElement element : matchersJson) {
            JsonObject matcher = element.getAsJsonObject();
            String exact = matcher.has("exact") ? matcher.get("exact").getAsString() : null;
            String contains = matcher.has("contains") ? matcher.get("contains").getAsString() : null;
            if ((exact == null) == (contains == null)
                    || (exact != null && exact.isBlank())
                    || (contains != null && contains.isBlank())) {
                throw new JsonParseException("each matcher requires exactly one non-empty exact/contains");
            }
            matchers.add(new Matcher(exact, contains, parseColor(matcher.get("color"), "matcher color")));
        }
        Map<Identifier, LightingWorldRule> rules = new LinkedHashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            int emission = block.getStateDefinition().getPossibleStates().stream()
                    .mapToInt(blockState -> blockState.getLightEmission()).max().orElse(0);
            if (emission <= 0) continue;
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            EmissionColor color = fallback;
            for (Matcher matcher : matchers) {
                if (matcher.matches(id.toString())) { color = matcher.color(); break; }
            }
            rules.put(id, new LightingWorldRule(color.red(), color.green(), color.blue()));
        }
        return rules;
    }

    private static LightingTuning parseTuning(JsonObject object) {
        if (object.size() != 2 || !object.has("brightness_multiplier")
                || !object.has("attenuation_multiplier")) {
            throw new JsonParseException("light_tuning requires brightness_multiplier and attenuation_multiplier only");
        }
        return new LightingTuning(object.get("brightness_multiplier").getAsFloat(),
                object.get("attenuation_multiplier").getAsFloat());
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
