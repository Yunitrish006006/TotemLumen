package dev.totem.lumen.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Versioned, bounded, read-only server-lighting preview protocol. */
public final class ServerLightingViewPackets {
    public static final int VERSION = 4;
    public static final int PAGE_SIZE = 96;
    public static final int MAX_PAGE_INDEX = (8_192 - 1) / PAGE_SIZE;
    public static final int MAX_PACKS = 128;
    public static final int BLOCKS = 0;
    public static final int SPAWN = 1;
    public static final int DIMENSIONS = 2;

    private ServerLightingViewPackets() {
    }

    /** Server-reported enabled data packs, ordered from low to high priority. */
    public record PackInfo(String id, String title, boolean hasTuning,
                           float brightnessMultiplier, float attenuationMultiplier) {
        public PackInfo {
            if (id == null || id.isBlank() || id.length() > 256 || title == null
                    || title.length() > 128) {
                throw new IllegalArgumentException("invalid lighting preview pack");
            }
            checkTuning(brightnessMultiplier, attenuationMultiplier);
        }
    }

    public record Summary(long revision, int spawnMode, int workBudget, long timeBudgetNanos,
                          boolean heldEnabled, int blockCount, int spawnCount, int dimensionCount,
                          int enabledPackCount, List<PackInfo> enabledPacks,
                          float brightnessMultiplier, float attenuationMultiplier)
            implements CustomPacketPayload {
        public static final Type<Summary> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath("totem-lumen", "server_lighting_summary"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Summary> CODEC =
                CustomPacketPayload.codec(Summary::write, Summary::new);

        public Summary {
            if (revision < 0 || spawnMode < 0 || spawnMode > 1 || workBudget < 1_000
                    || workBudget > 100_000 || timeBudgetNanos < 250_000L
                    || timeBudgetNanos > 5_000_000L || blockCount < 0 || blockCount > 8_192
                    || spawnCount < 0 || spawnCount > 8_192 || dimensionCount < 0
                    || dimensionCount > 8_192 || enabledPackCount < 0
                    || enabledPacks == null || enabledPacks.size() > MAX_PACKS
                    || enabledPackCount < enabledPacks.size()) {
                throw new IllegalArgumentException("invalid lighting preview summary");
            }
            checkTuning(brightnessMultiplier, attenuationMultiplier);
            enabledPacks = List.copyOf(enabledPacks);
        }

        private Summary(RegistryFriendlyByteBuf buffer) {
            this(buffer.readVarLong(), buffer.readUnsignedByte(), buffer.readVarInt(),
                    buffer.readVarLong(), buffer.readBoolean(), buffer.readVarInt(),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), readPacks(buffer),
                    buffer.readFloat(), buffer.readFloat());
            if (buffer.readVarInt() != VERSION) throw new IllegalArgumentException("preview protocol mismatch");
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarLong(revision);
            buffer.writeByte(spawnMode);
            buffer.writeVarInt(workBudget);
            buffer.writeVarLong(timeBudgetNanos);
            buffer.writeBoolean(heldEnabled);
            buffer.writeVarInt(blockCount);
            buffer.writeVarInt(spawnCount);
            buffer.writeVarInt(dimensionCount);
            buffer.writeVarInt(enabledPackCount);
            buffer.writeVarInt(enabledPacks.size());
            for (PackInfo pack : enabledPacks) {
                buffer.writeUtf(pack.id(), 256);
                buffer.writeUtf(pack.title(), 128);
                buffer.writeBoolean(pack.hasTuning());
                buffer.writeFloat(pack.brightnessMultiplier());
                buffer.writeFloat(pack.attenuationMultiplier());
            }
            buffer.writeFloat(brightnessMultiplier);
            buffer.writeFloat(attenuationMultiplier);
            buffer.writeVarInt(VERSION);
        }

        private static List<PackInfo> readPacks(RegistryFriendlyByteBuf buffer) {
            int count = buffer.readVarInt();
            if (count < 0 || count > MAX_PACKS) throw new IllegalArgumentException("invalid pack count");
            List<PackInfo> packs = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                packs.add(new PackInfo(buffer.readUtf(256), buffer.readUtf(128),
                        buffer.readBoolean(), buffer.readFloat(), buffer.readFloat()));
            }
            return packs;
        }

        @Override public Type<Summary> type() { return TYPE; }
    }

    private static void checkTuning(float brightness, float attenuation) {
        if (!Float.isFinite(brightness) || brightness < 0.0f || brightness > 4.0f
                || !Float.isFinite(attenuation) || attenuation < 1.0f || attenuation > 4.0f) {
            throw new IllegalArgumentException("invalid lighting tuning");
        }
    }

    public record PageRequest(long revision, int category, int page, String query, String packId)
            implements CustomPacketPayload {
        public static final Type<PageRequest> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath("totem-lumen", "server_lighting_page_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PageRequest> CODEC =
                CustomPacketPayload.codec(PageRequest::write, PageRequest::new);

        public PageRequest {
            if (revision < 0 || category < BLOCKS || category > DIMENSIONS || page < 0
                    || page > MAX_PAGE_INDEX || query == null || query.length() > 64
                    || packId == null || packId.length() > 256) {
                throw new IllegalArgumentException("invalid lighting preview page request");
            }
        }

        private PageRequest(RegistryFriendlyByteBuf buffer) {
            this(buffer.readVarLong(), buffer.readUnsignedByte(), buffer.readVarInt(),
                    buffer.readUtf(64), buffer.readUtf(256));
            if (buffer.readVarInt() != VERSION) throw new IllegalArgumentException("preview protocol mismatch");
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarLong(revision);
            buffer.writeByte(category);
            buffer.writeVarInt(page);
            buffer.writeUtf(query, 64);
            buffer.writeUtf(packId, 256);
            buffer.writeVarInt(VERSION);
        }

        @Override public Type<PageRequest> type() { return TYPE; }
    }

    public record Page(long revision, int category, int page, String query, String packId,
                       int total, List<Entry> entries)
            implements CustomPacketPayload {
        public static final Type<Page> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath("totem-lumen", "server_lighting_page"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Page> CODEC =
                CustomPacketPayload.codec(Page::write, Page::new);

        public Page {
            if (revision < 0 || category < BLOCKS || category > DIMENSIONS || page < 0
                    || page > MAX_PAGE_INDEX || query == null || query.length() > 64
                    || packId == null || packId.length() > 256
                    || total < 0 || total > 8_192 || entries == null
                    || entries.size() > PAGE_SIZE) {
                throw new IllegalArgumentException("invalid lighting preview page");
            }
            entries = List.copyOf(entries);
        }

        private Page(RegistryFriendlyByteBuf buffer) {
            this(buffer.readVarLong(), buffer.readUnsignedByte(), buffer.readVarInt(),
                    buffer.readUtf(64), buffer.readUtf(256), buffer.readVarInt(), readEntries(buffer));
            if (buffer.readVarInt() != VERSION) throw new IllegalArgumentException("preview protocol mismatch");
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarLong(revision);
            buffer.writeByte(category);
            buffer.writeVarInt(page);
            buffer.writeUtf(query, 64);
            buffer.writeUtf(packId, 256);
            buffer.writeVarInt(total);
            buffer.writeVarInt(entries.size());
            for (Entry entry : entries) entry.write(buffer);
            buffer.writeVarInt(VERSION);
        }

        private static List<Entry> readEntries(RegistryFriendlyByteBuf buffer) {
            int count = buffer.readVarInt();
            if (count < 0 || count > PAGE_SIZE) throw new IllegalArgumentException("invalid preview entry count");
            List<Entry> result = new ArrayList<>(count);
            for (int index = 0; index < count; index++) result.add(new Entry(buffer));
            return result;
        }

        @Override public Type<Page> type() { return TYPE; }
    }

    /** Six normalized channels: RGB plus optional environment/sensitivity RGB. */
    public record Entry(Identifier id, Identifier iconItemId, int origin, float red, float green, float blue,
                        float extraRed, float extraGreen, float extraBlue,
                        int strength, boolean flag, String detail) {
        public Entry {
            if (id == null || iconItemId == null || origin < 0 || origin > 3
                    || strength < -1 || strength > 15
                    || detail == null || detail.length() > 256) {
                throw new IllegalArgumentException("invalid preview entry");
            }
            for (float value : new float[]{red, green, blue, extraRed, extraGreen, extraBlue}) {
                if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
                    throw new IllegalArgumentException("invalid preview color");
                }
            }
        }

        private Entry(RegistryFriendlyByteBuf buffer) {
            this(buffer.readIdentifier(), buffer.readIdentifier(), buffer.readUnsignedByte(),
                    buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                    buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                    buffer.readVarInt(), buffer.readBoolean(), buffer.readUtf(256));
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeIdentifier(id);
            buffer.writeIdentifier(iconItemId);
            buffer.writeByte(origin);
            buffer.writeFloat(red);
            buffer.writeFloat(green);
            buffer.writeFloat(blue);
            buffer.writeFloat(extraRed);
            buffer.writeFloat(extraGreen);
            buffer.writeFloat(extraBlue);
            buffer.writeVarInt(strength);
            buffer.writeBoolean(flag);
            buffer.writeUtf(detail, 256);
        }
    }
}
