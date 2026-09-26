package dev.totem.lumen.gameplay.light;

import dev.totem.lumen.TotemLumen;
import dev.totem.lumen.config.ServerLightingConfig;
import dev.totem.lumen.config.ServerLightingConfigStore;
import dev.totem.lumen.network.ServerLightingViewPackets;
import dev.totem.lumen.world.EffectiveLightingRules;
import dev.totem.lumen.world.LightingWorldRule;
import dev.totem.lumen.world.LightingWorldRulesReloadListener;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Small summary on join/reload; bounded detail pages are sent only when the preview requests them. */
public final class ServerLightingViewService {
    private static final long MIN_REQUEST_INTERVAL_NANOS = 100_000_000L;
    private static final Map<UUID, Long> LAST_REQUEST_NANOS = new HashMap<>();
    private static List<SourceBlock> vanillaSources;

    private record SourceBlock(Identifier id, int emission, boolean defaultEmits) {
    }

    private ServerLightingViewService() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(ServerLightingViewPackets.PageRequest.TYPE,
                (request, context) -> context.server().execute(() ->
                        sendPage(context.server(), context.player(), request)));
    }

    public static void sendSummary(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, ServerLightingViewPackets.Summary.TYPE)) return;
        ServerPlayNetworking.send(player, summary(player.level().getServer()));
    }

    public static void broadcast(MinecraftServer server) {
        ServerLightingViewPackets.Summary summary = summary(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(player, ServerLightingViewPackets.Summary.TYPE)) {
                ServerPlayNetworking.send(player, summary);
            }
        }
    }

    public static void clear() {
        LAST_REQUEST_NANOS.clear();
        vanillaSources = null;
    }

    private static ServerLightingViewPackets.Summary summary(MinecraftServer server) {
        ServerLightingConfig config = ServerLightingConfigStore.current();
        List<Pack> enabled = server.getPackRepository().getSelectedPacks().stream().toList();
        List<ServerLightingViewPackets.PackInfo> packs = enabled.stream()
                .filter(pack -> isLightingPack(pack.getId()))
                .filter(pack -> pack.getId().length() <= 256)
                .limit(ServerLightingViewPackets.MAX_PACKS)
                .map(pack -> {
                    var tuning = LightingWorldRulesReloadListener.tuningForPack(pack.getId());
                    return new ServerLightingViewPackets.PackInfo(pack.getId(),
                            truncate(pack.getTitle().getString(), 128), tuning.isPresent(),
                            tuning.map(value -> value.brightnessMultiplier()).orElse(1.0f),
                            tuning.map(value -> value.attenuationMultiplier()).orElse(1.0f));
                })
                .toList();
        var effectiveTuning = LightingWorldRulesReloadListener.currentTuning();
        return new ServerLightingViewPackets.Summary(
                EffectiveLightingRules.current().revision(), config.spawnMode().ordinal(),
                config.workBudget(), config.timeBudgetNanos(), config.heldLightEnabled(),
                blockSources().size(),
                Math.min(8_192, SpawnLightProfilesReloadListener.currentProfiles().size()),
                Math.min(8_192, DimensionLightingRulesReloadListener.currentRules().size()),
                packs.size(), packs, effectiveTuning.brightnessMultiplier(),
                effectiveTuning.attenuationMultiplier());
    }

    private static boolean isLightingPack(String packId) {
        return LightingWorldRulesReloadListener.currentPackIds().contains(packId)
                || SpawnLightProfilesReloadListener.currentPackIds().contains(packId)
                || DimensionLightingRulesReloadListener.currentPackIds().contains(packId);
    }

    private static String truncate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static void sendPage(MinecraftServer server, ServerPlayer player,
                                 ServerLightingViewPackets.PageRequest request) {
        long now = System.nanoTime();
        Long last = LAST_REQUEST_NANOS.get(player.getUUID());
        if (last != null && now - last < MIN_REQUEST_INTERVAL_NANOS) return;
        LAST_REQUEST_NANOS.put(player.getUUID(), now);
        long revision = EffectiveLightingRules.current().revision();
        if (request.revision() != revision) {
            sendSummary(player);
            return;
        }
        if (!request.packId().isEmpty() && (server.getPackRepository().getSelectedPacks().stream()
                .noneMatch(pack -> pack.getId().equals(request.packId()) && isLightingPack(pack.getId())))) {
            sendSummary(player);
            return;
        }
        List<ServerLightingViewPackets.Entry> entries = switch (request.category()) {
            case ServerLightingViewPackets.BLOCKS -> blockEntries(request.packId());
            case ServerLightingViewPackets.SPAWN -> spawnEntries(request.packId());
            case ServerLightingViewPackets.DIMENSIONS -> dimensionEntries(request.packId());
            default -> List.of();
        };
        String query = request.query().trim().toLowerCase(java.util.Locale.ROOT);
        if (!query.isEmpty()) {
            entries = entries.stream().filter(entry ->
                    entry.id().toString().toLowerCase(java.util.Locale.ROOT).contains(query)).toList();
        }
        int offset = request.page() * ServerLightingViewPackets.PAGE_SIZE;
        List<ServerLightingViewPackets.Entry> page = offset >= entries.size() ? List.of()
                : entries.subList(offset, Math.min(entries.size(), offset + ServerLightingViewPackets.PAGE_SIZE));
        ServerPlayNetworking.send(player, new ServerLightingViewPackets.Page(
                revision, request.category(), request.page(), request.query(), request.packId(),
                entries.size(), page));
        TotemLumen.LOGGER.info("Sent server lighting preview page: category={}, page={}, total={}, entries={}",
                request.category(), request.page(), entries.size(), page.size());
    }

    private static List<SourceBlock> blockSources() {
        if (vanillaSources == null) {
            List<SourceBlock> found = new ArrayList<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                int maximum = block.getStateDefinition().getPossibleStates().stream()
                        .mapToInt(state -> state.getLightEmission()).max().orElse(0);
                if (maximum > 0) {
                    found.add(new SourceBlock(BuiltInRegistries.BLOCK.getKey(block), maximum,
                            block.defaultBlockState().getLightEmission() > 0));
                }
            }
            found.sort(Comparator.comparing(source -> source.id().toString()));
            vanillaSources = List.copyOf(found);
        }
        Set<Identifier> seen = new HashSet<>();
        List<SourceBlock> result = new ArrayList<>(vanillaSources);
        vanillaSources.forEach(source -> seen.add(source.id()));
        for (Identifier id : EffectiveLightingRules.current().rules().rules().keySet()) {
            if (seen.add(id)) result.add(new SourceBlock(id, 0, false));
        }
        result.sort(Comparator.comparing(source -> source.id().toString()));
        if (result.size() > 8_192) return result.subList(0, 8_192);
        return result;
    }

    private static List<ServerLightingViewPackets.Entry> blockEntries(String packId) {
        EffectiveLightingRules.Snapshot snapshot = EffectiveLightingRules.current();
        List<ServerLightingViewPackets.Entry> result = new ArrayList<>();
        List<SourceBlock> sources;
        if (packId.isEmpty()) {
            sources = blockSources();
        } else {
            sources = LightingWorldRulesReloadListener.rulesForPack(packId).rules().keySet().stream()
                    .sorted(Comparator.comparing(Identifier::toString))
                    .limit(8_192)
                    .map(id -> {
                        Block block = BuiltInRegistries.BLOCK.getValue(id);
                        int emission = block == null ? 0 : block.getStateDefinition().getPossibleStates()
                                .stream().mapToInt(state -> state.getLightEmission()).max().orElse(0);
                        return new SourceBlock(id, emission,
                                block != null && block.defaultBlockState().getLightEmission() > 0);
                    }).toList();
        }
        for (SourceBlock source : sources) {
            LightingWorldRule rule = packId.isEmpty() ? snapshot.rules().ruleFor(source.id())
                    : LightingWorldRulesReloadListener.rulesForPack(packId).ruleFor(source.id());
            EmissionColor color = rule == null
                    ? DefaultEmissionColors.forBlock(source.id().toString(), source.emission())
                    : new EmissionColor(rule.emissionR(), rule.emissionG(), rule.emissionB());
            int origin = !packId.isEmpty() && snapshot.origins().get(source.id())
                    != EffectiveLightingRules.Origin.SERVER_CONFIG
                    ? ("totem-lumen:default_lighting".equals(packId) ? 3 : 1)
                    : switch (snapshot.origins().get(source.id())) {
                case null -> 0;
                case DATA_PACK -> 1;
                case SERVER_CONFIG -> 2;
                case DEFAULT_DATA_PACK -> 3;
            };
            result.add(new ServerLightingViewPackets.Entry(source.id(), blockIcon(source.id()), origin,
                    color.red(), color.green(), color.blue(), 0, 0, 0,
                    rule == null ? -1 : rule.gameplayStrength(), source.defaultEmits(),
                    source.emission() == 0 ? "non_emissive" : ""));
        }
        return result;
    }

    private static List<ServerLightingViewPackets.Entry> spawnEntries(String packId) {
        List<ServerLightingViewPackets.Entry> result = new ArrayList<>();
        var profiles = packId.isEmpty() ? SpawnLightProfilesReloadListener.currentProfiles()
                : SpawnLightProfilesReloadListener.profilesForPack(packId);
        profiles.profiles().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Identifier::toString)))
                .limit(8_192)
                .forEach(entry -> {
                    SpawnLightProfile profile = entry.getValue();
                    GameplayLightSensitivity s = profile.sensitivity();
                    String detail = "priority=" + profile.priority() + " entities=" + profile.entities()
                            + " tags=" + profile.entityTagIds();
                    if (detail.length() > 256) detail = detail.substring(0, 256);
                    int origin = (packId.isEmpty()
                            ? SpawnLightProfilesReloadListener.currentDefaultIds().contains(entry.getKey())
                            : packId.equals("totem-lumen:default_lighting"))
                            ? 3 : 1;
                    result.add(new ServerLightingViewPackets.Entry(entry.getKey(), spawnIcon(profile), origin,
                            s.blockRed(), s.blockGreen(), s.blockBlue(),
                            s.environmentRed(), s.environmentGreen(), s.environmentBlue(),
                            -1, false, detail));
                });
        return result;
    }

    private static List<ServerLightingViewPackets.Entry> dimensionEntries(String packId) {
        List<ServerLightingViewPackets.Entry> result = new ArrayList<>();
        var rules = packId.isEmpty() ? DimensionLightingRulesReloadListener.currentRules()
                : DimensionLightingRulesReloadListener.rulesForPack(packId);
        rules.rules().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Identifier::toString)))
                .limit(8_192)
                .forEach(entry -> {
                    DimensionLightingRule rule = entry.getValue();
                    result.add(new ServerLightingViewPackets.Entry(entry.getKey(), dimensionIcon(entry.getKey()), 1,
                            rule.environmentColor().red(), rule.environmentColor().green(),
                            rule.environmentColor().blue(), 0, 0, 0,
                            rule.gameplayStrength(), rule.affectsSpawning(), ""));
                });
        return result;
    }

    private static Identifier itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    private static Identifier blockIcon(Identifier id) {
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        Item item = block == null ? Items.AIR : block.asItem();
        if (item != Items.AIR) return itemId(item);
        item = switch (id.getPath()) {
            case "wall_torch" -> Items.TORCH;
            case "soul_wall_torch" -> Items.SOUL_TORCH;
            case "redstone_wall_torch" -> Items.REDSTONE_TORCH;
            case "lava", "flowing_lava", "lava_cauldron" -> Items.LAVA_BUCKET;
            case "fire" -> Items.FLINT_AND_STEEL;
            case "soul_fire" -> Items.SOUL_TORCH;
            case "cave_vines", "cave_vines_plant" -> Items.GLOW_BERRIES;
            case "candle_cake" -> Items.CANDLE;
            case "nether_portal" -> Items.OBSIDIAN;
            case "end_portal", "end_gateway" -> Items.ENDER_EYE;
            default -> Items.GLOWSTONE_DUST;
        };
        return itemId(item);
    }

    private static Identifier spawnIcon(SpawnLightProfile profile) {
        for (Identifier id : profile.entities().stream().sorted(Comparator.comparing(Identifier::toString)).toList()) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
            if (type == null) continue;
            var egg = SpawnEggItem.byId(type);
            if (egg.isPresent()) return itemId(egg.get().value());
        }
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (!profile.matches(type)) continue;
            var egg = SpawnEggItem.byId(type);
            if (egg.isPresent()) return itemId(egg.get().value());
        }
        return itemId(Items.ZOMBIE_SPAWN_EGG);
    }

    private static Identifier dimensionIcon(Identifier id) {
        return itemId(switch (id.toString()) {
            case "minecraft:the_end" -> Items.ENDER_EYE;
            case "minecraft:the_nether" -> Items.BLAZE_POWDER;
            default -> Items.COMPASS;
        });
    }
}
