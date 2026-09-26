package dev.totem.lumen.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;

import java.util.ArrayList;
import java.util.List;

/** Bounded authoritative held-item light snapshot; entity motion uses vanilla tracking. */
public record HeldLightsPayload(Identifier dimension, List<Source> sources) implements CustomPacketPayload {
    public static final int MAX_SOURCES = 16;
    public static final Type<HeldLightsPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("totem-lumen", "held_lights")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, HeldLightsPayload> CODEC =
            CustomPacketPayload.codec(HeldLightsPayload::write, HeldLightsPayload::new);

    public HeldLightsPayload {
        if (dimension == null || sources == null || sources.size() > MAX_SOURCES) {
            throw new IllegalArgumentException("invalid held-light snapshot");
        }
        sources = List.copyOf(sources);
    }

    private HeldLightsPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readIdentifier(), readSources(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeIdentifier(dimension);
        buffer.writeVarInt(sources.size());
        for (Source source : sources) {
            buffer.writeVarInt(source.entityId());
            buffer.writeShort(source.packed() & 0xFFFF);
            buffer.writeByte(source.hand() == InteractionHand.MAIN_HAND ? 0 : 1);
        }
    }

    private static List<Source> readSources(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_SOURCES) throw new IllegalArgumentException("invalid held-light count");
        List<Source> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int entityId = buffer.readVarInt();
            char packed = (char) buffer.readUnsignedShort();
            int handId = buffer.readUnsignedByte();
            if (handId > 1) throw new IllegalArgumentException("invalid held-light hand");
            result.add(new Source(entityId, packed,
                    handId == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND));
        }
        return result;
    }

    @Override
    public Type<HeldLightsPayload> type() {
        return TYPE;
    }

    public record Source(int entityId, char packed, InteractionHand hand) {
        public Source {
            if (entityId < 0 || packed == 0 || hand == null) {
                throw new IllegalArgumentException("invalid held light");
            }
        }
    }
}
