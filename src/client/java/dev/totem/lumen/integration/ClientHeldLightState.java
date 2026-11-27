package dev.totem.lumen.integration;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.gameplay.light.GameplayLightSource;
import dev.totem.lumen.gameplay.light.HeldLightSource;
import dev.totem.lumen.gameplay.light.PackedRgbLight;
import dev.totem.lumen.network.HeldLightsPayload;
import dev.totem.lumen.network.ServerLightingViewPackets;
import dev.totem.lumen.render.RendererSettings;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Tracked hand equipment resolved against interpolated entity positions. */
public final class ClientHeldLightState {
    private static volatile HeldLightsPayload latest;
    private static volatile List<Light> captured = List.of();
    private static volatile Boolean enabled;
    private static int diagnosticChanges;

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    private ClientHeldLightState() {
    }

    public static void apply(HeldLightsPayload payload) {
        if (!payload.equals(latest) && diagnosticChanges++ < 12) {
            TotemLumenClient.LOGGER.info("Held light snapshot received: sources={}, dimension={}",
                    payload.sources().size(), payload.dimension());
        }
        latest = payload;
    }

    public static void clear() {
        latest = null;
        enabled = null;
        captured = List.of();
        diagnosticChanges = 0;
    }

    public static List<Light> capture(Minecraft client, float partialTick) {
        HeldLightsPayload payload = latest;
        if (RendererSettings.renderProfile() == RendererSettings.RenderProfile.MINECRAFT_PURE
                || client.level == null || client.player == null) {
            captured = List.of();
            return captured;
        }
        boolean rgb = RendererSettings.renderProfile() == RendererSettings.RenderProfile.MINECRAFT_RGB;
        boolean lumia = RendererSettings.lumiaEnabled();
        List<Light> result = new ArrayList<>();
        // Older modded servers still own their equipment list. An empty snapshot can mean disabled.
        Boolean policy = enabled;
        boolean vanilla = payload == null && client.getConnection() != null
                && !ClientPlayNetworking.canSend(ServerLightingViewPackets.PageRequest.TYPE);
        if ((rgb || lumia) && (Boolean.TRUE.equals(policy) || (policy == null && vanilla))) {
            var viewer = client.player;
            for (LivingEntity entity : client.level.getEntitiesOfClass(LivingEntity.class,
                    viewer.getBoundingBox().inflate(48), e -> e.distanceToSqr(viewer) <= 48 * 48)) {
                for (InteractionHand hand : InteractionHand.values()) {
                    var state = HeldLightSource.emittingState(entity.getItemInHand(hand));
                    if (state == null) continue;
                    var rule = ClientLightingWorldRules.ruleFor(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                    char packed = GameplayLightSource.packedFor(state, rule);
                    if (packed != 0) addLight(result, entity, hand, packed, partialTick);
                }
            }
        } else if ((!rgb && !lumia || policy == null) && payload != null
                && payload.dimension().equals(client.level.dimension().identifier())) {
            for (HeldLightsPayload.Source source : payload.sources()) {
                Entity entity = client.level.getEntity(source.entityId());
                if (entity instanceof LivingEntity living && !living.isRemoved())
                    addLight(result, living, source.hand(), source.packed(), partialTick);
            }
        }
        if (!rgb) {
            Vec3 viewer = client.player.position();
            result.sort(Comparator.comparingDouble(light -> {
                double dx = light.x() - viewer.x;
                double dy = light.y() - viewer.y;
                double dz = light.z() - viewer.z;
                return dx * dx + dy * dy + dz * dz;
            }));
        }
        captured = List.copyOf(result);
        return captured;
    }

    private static void addLight(List<Light> result, LivingEntity holder, InteractionHand hand,
                                 char packed, float partialTick) {
        Vec3 eye = holder.getEyePosition(partialTick);
        float yaw = Mth.rotLerp(partialTick, holder.yBodyRotO, holder.yBodyRot)
                * ((float) Math.PI / 180.0f);
        float armSide = (hand == InteractionHand.MAIN_HAND
                ? holder.getMainArm() : holder.getMainArm().getOpposite()) == HumanoidArm.RIGHT
                ? 1.0f : -1.0f;
        // Match the approximate held-item location in world space for every camera mode.
        float side = 0.34f * armSide;
        float forward = 0.27f;
        double x = eye.x - Mth.cos(yaw) * side - Mth.sin(yaw) * forward;
        double z = eye.z - Mth.sin(yaw) * side + Mth.cos(yaw) * forward;
        float radius = PackedRgbLight.alpha(packed) + 0.5f;
        result.add(new Light(
                holder.getId(), packed, x, eye.y - 0.45f, z,
                radius,
                PackedRgbLight.red(packed) / 15.0f,
                PackedRgbLight.green(packed) / 15.0f,
                PackedRgbLight.blue(packed) / 15.0f
        ));
    }

    public static List<Light> captured() {
        return captured;
    }

    public record Light(int entityId, char packed, double x, double y, double z, float radius,
                        float red, float green, float blue) {
    }
}
