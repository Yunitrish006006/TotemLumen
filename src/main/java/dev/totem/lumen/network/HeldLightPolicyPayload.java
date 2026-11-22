package dev.totem.lumen.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Allows clients to light tracked equipment while respecting the server setting. */
public record HeldLightPolicyPayload(boolean enabled) implements CustomPacketPayload {
    public static final Type<HeldLightPolicyPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("totem-lumen", "held_light_policy"));
    public static final StreamCodec<RegistryFriendlyByteBuf, HeldLightPolicyPayload> CODEC =
            CustomPacketPayload.codec((value, buf) -> buf.writeBoolean(value.enabled()),
                    buf -> new HeldLightPolicyPayload(buf.readBoolean()));

    @Override
    public Type<HeldLightPolicyPayload> type() {
        return TYPE;
    }
}
