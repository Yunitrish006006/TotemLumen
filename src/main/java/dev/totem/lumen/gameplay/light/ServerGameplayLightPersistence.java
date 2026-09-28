package dev.totem.lumen.gameplay.light;

import dev.totem.lumen.TotemLumen;
import dev.totem.lumen.world.EffectiveLightingRules;
import dev.totem.lumen.world.LightingWorldRulesReloadListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * World-local warm cache for the deterministic gameplay RGB field.
 *
 * <p>The cache is deliberately non-authoritative. It is accepted only when both the solver
 * version and the effective lighting-rule hash match, then every loaded chunk is rescanned and
 * reconciled by {@link ServerGameplayLightEngine}. The persisted source index removes the
 * discovery delay while the stable section cache prevents a dark flash on world re-entry.</p>
 */
final class ServerGameplayLightPersistence {
    private static final int MAGIC = 0x544C5247; // TLRG
    private static final int FORMAT_VERSION = 1;
    private static final int SOLVER_VERSION = 1;
    private static final int MAX_SOURCES = 1_000_000;
    private static final int MAX_SECTIONS = 65_536;
    private static final long MAX_COMPRESSED_BYTES = 256L * 1024L * 1024L;

    private ServerGameplayLightPersistence() {
    }

    static Optional<ServerGameplayLightEngine.WarmState> load(ServerLevel level) {
        Path path = cachePath(level);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            if (Files.size(path) > MAX_COMPRESSED_BYTES) {
                TotemLumen.LOGGER.warn(
                        "Ignoring oversized gameplay-light warm cache for {}: {}",
                        level.dimension().identifier(),
                        path
                );
                return Optional.empty();
            }
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                    new GZIPInputStream(Files.newInputStream(path))
            ))) {
                if (input.readInt() != MAGIC) {
                    return Optional.empty();
                }
                int formatVersion = input.readInt();
                int solverVersion = input.readInt();
                int savedRulesHash = input.readInt();
                if (formatVersion != FORMAT_VERSION
                        || solverVersion != SOLVER_VERSION
                        || savedRulesHash != rulesHash()) {
                    TotemLumen.LOGGER.info(
                            "Discarded stale gameplay-light warm cache for {} "
                                    + "(format={}, solver={}, rulesMatch={})",
                            level.dimension().identifier(),
                            formatVersion,
                            solverVersion,
                            savedRulesHash == rulesHash()
                    );
                    return Optional.empty();
                }

                long revision = input.readLong();
                if (revision < 0L) {
                    throw new IOException("negative gameplay-light revision");
                }

                int sourceCount = boundedCount(input.readInt(), MAX_SOURCES, "source");
                List<ServerGameplayLightEngine.SourceSnapshot> sources =
                        new ArrayList<>(sourceCount);
                for (int index = 0; index < sourceCount; index++) {
                    sources.add(new ServerGameplayLightEngine.SourceSnapshot(
                            input.readInt(),
                            input.readInt(),
                            input.readInt(),
                            input.readChar()
                    ));
                }

                int sectionCount = boundedCount(input.readInt(), MAX_SECTIONS, "section");
                List<ServerGameplayLightEngine.SectionSnapshot> sections =
                        new ArrayList<>(sectionCount);
                for (int index = 0; index < sectionCount; index++) {
                    int x = input.readInt();
                    int y = input.readInt();
                    int z = input.readInt();
                    char[] values = new char[ServerLightSection.VOXEL_COUNT];
                    for (int cell = 0; cell < values.length; cell++) {
                        values[cell] = input.readChar();
                    }
                    sections.add(new ServerGameplayLightEngine.SectionSnapshot(x, y, z, values));
                }

                TotemLumen.LOGGER.info(
                        "Loaded gameplay-light warm cache for {}: sources={}, sections={}, revision={}",
                        level.dimension().identifier(),
                        sources.size(),
                        sections.size(),
                        revision
                );
                return Optional.of(new ServerGameplayLightEngine.WarmState(
                        revision,
                        sources,
                        sections
                ));
            }
        } catch (IOException | RuntimeException exception) {
            TotemLumen.LOGGER.warn(
                    "Ignoring unreadable gameplay-light warm cache for {}: {}",
                    level.dimension().identifier(),
                    exception.toString()
            );
            return Optional.empty();
        }
    }

    static void save(ServerLevel level, ServerGameplayLightEngine.WarmState state) {
        if (state.sources().size() > MAX_SOURCES || state.sections().size() > MAX_SECTIONS) {
            TotemLumen.LOGGER.warn(
                    "Skipping gameplay-light warm cache for {} because it exceeds bounds: "
                            + "sources={}, sections={}",
                    level.dimension().identifier(),
                    state.sources().size(),
                    state.sections().size()
            );
            return;
        }

        Path path = cachePath(level);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                    new GZIPOutputStream(Files.newOutputStream(temporary))
            ))) {
                output.writeInt(MAGIC);
                output.writeInt(FORMAT_VERSION);
                output.writeInt(SOLVER_VERSION);
                output.writeInt(rulesHash());
                output.writeLong(state.revision());

                output.writeInt(state.sources().size());
                for (ServerGameplayLightEngine.SourceSnapshot source : state.sources()) {
                    output.writeInt(source.x());
                    output.writeInt(source.y());
                    output.writeInt(source.z());
                    output.writeChar(source.packedRgb());
                }

                output.writeInt(state.sections().size());
                for (ServerGameplayLightEngine.SectionSnapshot section : state.sections()) {
                    output.writeInt(section.x());
                    output.writeInt(section.y());
                    output.writeInt(section.z());
                    for (char value : section.values()) {
                        output.writeChar(value);
                    }
                }
            }

            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            TotemLumen.LOGGER.warn(
                    "Failed to save gameplay-light warm cache for {}: {}",
                    level.dimension().identifier(),
                    exception.toString()
            );
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Best-effort cleanup only.
            }
        }
    }

    private static int boundedCount(int count, int maximum, String label) throws IOException {
        if (count < 0 || count > maximum) {
            throw new IOException("invalid gameplay-light " + label + " count: " + count);
        }
        return count;
    }

    private static int rulesHash() {
        int hash = EffectiveLightingRules.current().rules().rules().hashCode();
        return 31 * hash + LightingWorldRulesReloadListener.currentTuning().hashCode();
    }

    private static Path cachePath(ServerLevel level) {
        String dimension = level.dimension().identifier().toString();
        String safeName = dimension.replaceAll("[^a-zA-Z0-9._-]", "_")
                + "-" + Integer.toUnsignedString(dimension.hashCode(), 16);
        return level.getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve("totem-lumen")
                .resolve("gameplay-light")
                .resolve(safeName + ".bin.gz");
    }
}
