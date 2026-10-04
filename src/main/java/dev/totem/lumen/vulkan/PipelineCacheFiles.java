package dev.totem.lumen.vulkan;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Module-private disk I/O for opaque driver caches; has no client or Vulkan dependency. */
final class PipelineCacheFiles {
    static final int MAX_BYTES = 64 * 1024 * 1024;

    private PipelineCacheFiles() {}

    static byte[] read(Path file) throws IOException {
        return read(file, MAX_BYTES);
    }

    static byte[] read(Path file, int limit) throws IOException {
        if (limit < 1 || limit > MAX_BYTES) throw new IllegalArgumentException("Invalid cache limit");
        // Preserve the cache store's non-file exclusion (e.g. directories and named pipes).
        // This is not the size bound: that check belongs to the open handle below.
        if (!Files.isRegularFile(file)) return null;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            // Size and content belong to the same open file even if another process replaces its path.
            long size = channel.size();
            if (size < 1 || size > limit) throw new IOException("Invalid pipeline cache size: " + size);
            // Retain a hard bound even if a different writer grows this inode in place.
            byte[] bytes = Channels.newInputStream(channel).readNBytes(limit + 1);
            if (bytes.length < 1 || bytes.length > limit)
                throw new IOException("Pipeline cache changed to an invalid size while reading");
            return bytes;
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    static void write(Path file, byte[] bytes) throws IOException {
        write(file, bytes, PipelineCacheFiles::publish);
    }

    @FunctionalInterface
    interface Publisher {
        void move(Path temporary, Path target) throws IOException;
    }

    static void publish(Path temporary, Path target) throws IOException {
        // If atomic replacement is unsupported, preserve the old cache and let the caller warn.
        // A non-atomic fallback could expose a partially replaced blob to another game process.
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static void write(Path file, byte[] bytes, Publisher publisher) throws IOException {
        if (bytes.length < 1 || bytes.length > MAX_BYTES)
            throw new IOException("Invalid pipeline cache write size: " + bytes.length);
        Path target = file.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        Throwable failure = null;
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var data = ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            publisher.move(temporary, target);
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            try {
                // Only this operation's unique staging file; never another writer's staging path.
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                if (failure != null) failure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }
}
