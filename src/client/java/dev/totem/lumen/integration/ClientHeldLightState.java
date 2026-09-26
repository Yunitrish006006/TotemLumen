package dev.totem.lumen.integration;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.gameplay.light.PackedRgbLight;
import dev.totem.lumen.network.HeldLightsPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Server-approved hand equipment resolved against interpolated client entity positions. */
public final class ClientHeldLightState {
    private static volatile HeldLightsPayload latest;
    private static volatile List<Light> captured = List.of();
    private static int diagnosticChanges;

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
        captured = List.of();
        diagnosticChanges = 0;
    }

    public static List<Light> capture(Minecraft client, float partialTick) {
        HeldLightsPayload payload = latest;
        if (payload == null || client.level == null
                || !payload.dimension().equals(client.level.dimension().identifier())) {
            captured = List.of();
            return captured;
        }
        List<Light> result = new ArrayList<>(payload.sources().size());
        for (HeldLightsPayload.Source source : payload.sources()) {
            Entity entity = client.level.getEntity(source.entityId());
            if (!(entity instanceof Player player) || entity.isRemoved()) continue;
            Vec3 eye = entity.getEyePosition(partialTick);
            float yaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot)
                    * ((float) Math.PI / 180.0f);
            float armSide = (source.hand() == InteractionHand.MAIN_HAND
                    ? player.getMainArm() : player.getMainArm().getOpposite()) == HumanoidArm.RIGHT
                    ? 1.0f : -1.0f;
            // Match the approximate held-item location in world space for every camera mode.
            float side = 0.34f * armSide;
            float forward = 0.27f;
            float x = (float) eye.x - Mth.cos(yaw) * side - Mth.sin(yaw) * forward;
            float z = (float) eye.z - Mth.sin(yaw) * side + Mth.cos(yaw) * forward;
            int packed = source.packed();
            float radius = PackedRgbLight.alpha(packed) + 0.5f;
            result.add(new Light(
                    source.entityId(), x, (float) eye.y - 0.45f, z,
                    radius,
                    PackedRgbLight.red(packed) / 15.0f,
                    PackedRgbLight.green(packed) / 15.0f,
                    PackedRgbLight.blue(packed) / 15.0f
            ));
        }
        if (client.player != null) {
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

    public static List<Light> captured() {
        return captured;
    }

    public record Light(int entityId, float x, float y, float z, float radius,
                        float red, float green, float blue) {
    }
}
