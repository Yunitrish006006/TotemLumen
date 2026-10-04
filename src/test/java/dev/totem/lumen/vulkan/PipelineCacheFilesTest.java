package dev.totem.lumen.vulkan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class PipelineCacheFilesTest {
    @TempDir Path directory;

    @Test void missingCacheIsAMiss() throws Exception {
        assertNull(PipelineCacheFiles.read(directory.resolve("missing.bin")));
        assertNull(PipelineCacheFiles.read(directory));
    }

    @Test void replacesCompleteBlobAndLeavesNoStagingFile() throws Exception {
        Path file = directory.resolve("nested/cache.bin");
        PipelineCacheFiles.write(file, new byte[]{1, 2, 3});
        PipelineCacheFiles.write(file, new byte[]{4});
        assertArrayEquals(new byte[]{4}, PipelineCacheFiles.read(file));
        try (var entries = Files.list(file.getParent())) {
            assertEquals(List.of(file), entries.toList());
        }
    }

    @Test void rejectsEmptyAndOversizedReadsWithoutDeletingCache() throws Exception {
        Path file = directory.resolve("cache.bin");
        Files.write(file, new byte[0]);
        assertThrows(IOException.class, () -> PipelineCacheFiles.read(file));
        Files.write(file, new byte[]{1, 2, 3, 4, 5});
        assertThrows(IOException.class, () -> PipelineCacheFiles.read(file, 4));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, Files.readAllBytes(file));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, PipelineCacheFiles.read(file, 5));
    }

    @Test void invalidWriteDoesNotTouchExistingCache() throws Exception {
        Path file = directory.resolve("cache.bin");
        PipelineCacheFiles.write(file, new byte[]{7});
        assertThrows(IOException.class, () -> PipelineCacheFiles.write(file, new byte[0]));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(file));
        try (var entries = Files.list(directory)) {
            assertEquals(1, entries.count());
        }
    }

    @Test void unsupportedAtomicMoveKeepsOldCacheAndCleansOnlyOwnTemporary() throws Exception {
        Path file = directory.resolve("cache.bin");
        Path otherWriter = directory.resolve("cache.bin.other-writer.tmp");
        PipelineCacheFiles.write(file, new byte[]{7});
        Files.write(otherWriter, new byte[]{9});
        assertThrows(AtomicMoveNotSupportedException.class, () -> PipelineCacheFiles.write(
                file, new byte[]{8}, (temporary, target) -> {
                    assertArrayEquals(new byte[]{8}, Files.readAllBytes(temporary));
                    throw new AtomicMoveNotSupportedException(temporary.toString(), target.toString(), "test");
                }));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(file));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(otherWriter));
        try (var entries = Files.list(directory)) {
            assertEquals(Set.of(file, otherWriter), Set.copyOf(entries.toList()));
        }
    }

    @Test void failedReplacementPreservesOriginalErrorAndOldBytes() throws Exception {
        Path file = directory.resolve("cache.bin");
        PipelineCacheFiles.write(file, new byte[]{7});
        IOException expected = new IOException("injected publish failure");
        assertSame(expected, assertThrows(IOException.class, () -> PipelineCacheFiles.write(
                file, new byte[]{8}, (temporary, target) -> { throw expected; })));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(file));
        try (var entries = Files.list(directory)) {
            assertEquals(List.of(file), entries.toList());
        }
    }

    @Test void overlappingWritersStageIndependentlyAndReadersSeeWholeBlobs() throws Exception {
        Path file = directory.resolve("cache.bin");
        byte[] old = new byte[]{7};
        byte[] first = new byte[32_768], second = new byte[65_536];
        Arrays.fill(first, (byte) 1);
        Arrays.fill(second, (byte) 2);
        PipelineCacheFiles.write(file, old);
        var staged = new CountDownLatch(2);
        var publish = new CountDownLatch(1);
        Set<Path> temporaries = ConcurrentHashMap.newKeySet();
        PipelineCacheFiles.Publisher publisher = (temporary, target) -> {
            temporaries.add(temporary);
            staged.countDown();
            try {
                if (!publish.await(5, TimeUnit.SECONDS)) throw new IOException("publish gate timeout");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException(error);
            }
            PipelineCacheFiles.publish(temporary, target);
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { PipelineCacheFiles.write(file, first, publisher); return true; });
            var b = executor.submit(() -> { PipelineCacheFiles.write(file, second, publisher); return true; });
            try {
                assertTrue(staged.await(5, TimeUnit.SECONDS));
                assertEquals(2, temporaries.size());
                assertArrayEquals(old, PipelineCacheFiles.read(file));
            } finally {
                publish.countDown();
            }
            for (int i = 0; i < 100; i++) {
                byte[] observed = PipelineCacheFiles.read(file);
                assertTrue(Arrays.equals(old, observed) || Arrays.equals(first, observed)
                        || Arrays.equals(second, observed), "Reader saw a partial/mixed blob");
            }
            assertTrue(a.get(5, TimeUnit.SECONDS));
            assertTrue(b.get(5, TimeUnit.SECONDS));
            byte[] result = PipelineCacheFiles.read(file);
            assertTrue(Arrays.equals(first, result) || Arrays.equals(second, result));
        }
        try (var entries = Files.list(directory)) {
            assertEquals(List.of(file), entries.toList());
        }
    }
}
