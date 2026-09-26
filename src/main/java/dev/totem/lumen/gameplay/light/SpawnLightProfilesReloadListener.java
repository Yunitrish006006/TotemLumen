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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads server-only entity spectral response profiles from world data packs. */
public final class SpawnLightProfilesReloadListener extends SimpleReloadListener<SpawnLightProfilesReloadListener.Prepared> {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(TotemLumen.MOD_ID, "spawn_light_profiles");
    private static final FileToIdConverter CONVERTER = FileToIdConverter.json("totem_lumen/spawn_light");
    private static volatile SpawnLightProfileSet currentProfiles = SpawnLightProfileSet.EMPTY;
    private static volatile Set<Identifier> currentDefaultIds = Set.of();
    private static volatile Map<String, SpawnLightProfileSet> currentPackProfiles = Map.of();

    record Prepared(SpawnLightProfileSet profiles, Set<Identifier> defaultIds,
                    Map<String, SpawnLightProfileSet> packProfiles) {}

    @Override
    protected Prepared prepare(SharedState state) {
        Map<Identifier, SpawnLightProfile> loaded = new LinkedHashMap<>();
        Map<String, Map<Identifier, SpawnLightProfile>> byPack = new LinkedHashMap<>();
        Set<Identifier> defaultIds = new LinkedHashSet<>();
        for (Map.Entry<Identifier, Resource> entry : CONVERTER
                .listMatchingResources(state.resourceManager())
                .entrySet()) {
            Identifier resourceId = entry.getKey();
            Identifier profileId = CONVERTER.fileToId(resourceId);
            try (Reader reader = entry.getValue().openAsReader()) {
                loaded.put(profileId, parse(profileId, StrictJsonParser.parse(reader)));
                if (entry.getValue().sourcePackId().equals(TotemLumen.MOD_ID + ":default_lighting")) {
                    defaultIds.add(profileId);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to read spawn-light profile " + resourceId, exception);
            } catch (JsonParseException | IllegalArgumentException exception) {
                throw new JsonParseException(
                        "Invalid Totem Lumen spawn-light profile " + resourceId + ": " + exception.getMessage(),
                        exception
                );
            }
            for (Resource layer : state.resourceManager().getResourceStack(resourceId)) {
                try (Reader reader = layer.openAsReader()) {
                    byPack.computeIfAbsent(layer.sourcePackId(), ignored -> new LinkedHashMap<>())
                            .put(profileId, parse(profileId, StrictJsonParser.parse(reader)));
                } catch (IOException | RuntimeException exception) {
                    TotemLumen.LOGGER.warn("Ignoring shadowed spawn-light profile {} in pack {}: {}",
                            profileId, layer.sourcePackId(), exception.toString());
                }
            }
        }
        Map<String, SpawnLightProfileSet> snapshots = new LinkedHashMap<>();
        byPack.forEach((id, rules) -> snapshots.put(id, SpawnLightProfileSet.of(rules)));
        return new Prepared(SpawnLightProfileSet.of(loaded), Set.copyOf(defaultIds), Map.copyOf(snapshots));
    }

    @Override
    protected void apply(Prepared prepared, SharedState state) {
        currentProfiles = prepared.profiles();
        currentDefaultIds = prepared.defaultIds();
        currentPackProfiles = prepared.packProfiles();
        TotemLumen.LOGGER.info("Loaded {} server spawn-light profile(s), including {} default data-pack profile(s)",
                prepared.profiles().size(), prepared.defaultIds().size());
    }

    public static SpawnLightProfileSet currentProfiles() {
        return currentProfiles;
    }

    public static Set<Identifier> currentDefaultIds() {
        return currentDefaultIds;
    }

    public static SpawnLightProfileSet profilesForPack(String packId) {
        return currentPackProfiles.getOrDefault(packId, SpawnLightProfileSet.EMPTY);
    }

    public static Set<String> currentPackIds() {
        return Set.copyOf(currentPackProfiles.keySet());
    }

    public static void reset() {
        currentProfiles = SpawnLightProfileSet.EMPTY;
        currentDefaultIds = Set.of();
        currentPackProfiles = Map.of();
    }

    private static SpawnLightProfile parse(Identifier profileId, JsonElement root) {
        if (!root.isJsonObject()) {
            throw new JsonParseException("root must be a JSON object");
        }
        JsonObject object = root.getAsJsonObject();
        int priority = readInteger(object.get("priority"), "priority", 0);
        Set<Identifier> entities = readIdentifiers(object.get("entities"), "entities");
        List<Identifier> tags = List.copyOf(readIdentifiers(object.get("entity_tags"), "entity_tags"));
        float[] block = readSensitivity(object.get("block_sensitivity"), "block_sensitivity");
        float[] environment = object.has("environment_sensitivity")
                ? readSensitivity(object.get("environment_sensitivity"), "environment_sensitivity")
                : block.clone();

        try {
            return new SpawnLightProfile(
                    priority,
                    entities,
                    tags,
                    new GameplayLightSensitivity(
                            block[0], block[1], block[2],
                            environment[0], environment[1], environment[2]
                    )
            );
        } catch (IllegalArgumentException exception) {
            throw new JsonParseException("profile " + profileId + ": " + exception.getMessage(), exception);
        }
    }

    private static int readInteger(JsonElement element, String field, int fallback) {
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(field + " must be an integer");
        }
        double numeric = element.getAsDouble();
        int value = element.getAsInt();
        if (!Double.isFinite(numeric) || numeric != value) {
            throw new JsonParseException(field + " must be an integer");
        }
        return value;
    }

    private static Set<Identifier> readIdentifiers(JsonElement element, String field) {
        if (element == null) {
            return Set.of();
        }
        if (!element.isJsonArray()) {
            throw new JsonParseException(field + " must be an array of identifiers");
        }
        Set<Identifier> result = new LinkedHashSet<>();
        for (JsonElement value : element.getAsJsonArray()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new JsonParseException(field + " must contain only identifier strings");
            }
            result.add(Identifier.parse(value.getAsString()));
        }
        return result;
    }

    private static float[] readSensitivity(JsonElement element, String field) {
        if (element == null || !element.isJsonArray()) {
            throw new JsonParseException(field + " must be an array of exactly three numbers");
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() != 3) {
            throw new JsonParseException(field + " must contain exactly three numbers");
        }
        List<Float> values = new ArrayList<>(3);
        for (JsonElement value : array) {
            float parsed = value.getAsFloat();
            if (!Float.isFinite(parsed) || parsed < 0.0f || parsed > 1.0f) {
                throw new JsonParseException(field + " components must be finite values in [0, 1]");
            }
            values.add(parsed);
        }
        return new float[]{values.get(0), values.get(1), values.get(2)};
    }
}
