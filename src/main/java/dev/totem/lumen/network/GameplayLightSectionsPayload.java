package dev.totem.lumen.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Bounded server-authoritative RGB light-field section snapshot for vanilla rendering. */
public record GameplayLightSectionsPayload(
        Identifier dimension,
        List<Section> sections,
        boolean fullSync
) implements CustomPacketPayload {
    public static final int VOXEL_COUNT = 16 * 16 * 16;
    private static final int MAX_SECTIONS = 2048;
    public static final Type<GameplayLightSectionsPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("totem-lumen", "gameplay_light_sections")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, GameplayLightSectionsPayload> CODEC =
            CustomPacketPayload.codec(GameplayLightSectionsPayload::write, GameplayLightSectionsPayload::new);

    public GameplayLightSectionsPayload {
        if (dimension == null || sections == null || sections.size() > MAX_SECTIONS) {
            throw new IllegalArgumentException("invalid gameplay light section payload");
        }
        sections = List.copyOf(sections);
    }

    private GameplayLightSectionsPayload(RegistryFriendlyByteBuf buffer) {
        this(
                buffer.readIdentifier(),
                readSections(buffer),
                buffer.readBoolean()
        );
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeIdentifier(dimension);
        buffer.writeVarInt(sections.size());
        for (Section section : sections) {
            buffer.writeInt(section.x());
            buffer.writeInt(section.y());
            buffer.writeInt(section.z());
            char[] values = section.values();
            for (char value : values) {
                buffer.writeChar(value);
            }
        }
        buffer.writeBoolean(fullSync);
    }

    private static List<Section> readSections(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_SECTIONS) {
            throw new IllegalArgumentException("invalid gameplay light section count: " + count);
        }
        List<Section> sections = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int x = buffer.readInt();
            int y = buffer.readInt();
            int z = buffer.readInt();
            char[] values = new char[VOXEL_COUNT];
            for (int cell = 0; cell < values.length; cell++) {
                values[cell] = buffer.readChar();
            }
            sections.add(new Section(x, y, z, values));
        }
        return sections;
    }

    @Override
    public Type<GameplayLightSectionsPayload> type() {
        return TYPE;
    }

    public record Section(int x, int y, int z, char[] values) {
        public Section {
            if (values == null || values.length != VOXEL_COUNT) {
                throw new IllegalArgumentException("gameplay light section must contain 4096 cells");
            }
            values = values.clone();
        }
    }
}
